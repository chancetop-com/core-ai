package ai.core.server.task;

import ai.core.server.domain.BackgroundTask;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Execution engine for background maintenance tasks.
 *
 * <p>Guarantees at-most-once execution per task-id via MongoDB insert-or-skip:
 * the first pod to insert a RUNNING record wins; other pods see a
 * {@code DuplicateKeyException} and skip.  No PENDING state, no timeout recovery —
 * stuck RUNNING records are reset manually via {@link TaskController#retry}.</p>
 *
 * @author cyril
 */
public class TaskRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskRunner.class);

    @Inject
    MongoCollection<BackgroundTask> taskCollection;

    private final String podName;
    private final Map<String, AbstractTask> tasks = new ConcurrentHashMap<>();

    public TaskRunner() {
        this.podName = resolvePodName();
    }

    /**
     * Register a task so it can be looked up by type (e.g. for retry).
     */
    public void register(AbstractTask task) {
        tasks.put(task.type(), task);
    }

    /**
     * Look up a registered task by its type.
     */
    public AbstractTask getTask(String type) {
        return tasks.get(type);
    }

    /**
     * Run a task, identified by {@code taskId} (format: {@code {type}:{date}}).
     * Safe to call from multiple pods — only one will execute.
     */
    public void run(AbstractTask task, String taskId) {
        var record = new BackgroundTask();
        record.id = taskId;
        record.type = task.type();
        record.status = TaskStatus.RUNNING;
        record.claimedBy = podName;
        record.startedAt = ZonedDateTime.now();
        record.retryCount = 0;

        try {
            taskCollection.insert(record);
        } catch (MongoWriteException e) {
            if (e.getCode() == 11000) { // DuplicateKey
                LOGGER.debug("task already claimed, skipping, taskId={}", taskId);
                return;
            }
            throw e;
        }

        executeAndUpdate(task, taskId, record, 0);
    }

    /**
     * Retry a previously failed task.
     * Atomically transitions FAILED → RUNNING, then re-executes.
     *
     * @return true if the retry was accepted (task was in FAILED state), false otherwise
     */
    public boolean retry(AbstractTask task, String taskId) {
        var existing = taskCollection.get(taskId);
        if (existing.isEmpty() || existing.get().status != TaskStatus.FAILED) {
            return false;
        }

        var record = existing.get();
        long updated = taskCollection.update(
            Filters.and(
                Filters.eq("_id", taskId),
                Filters.eq("status", TaskStatus.FAILED)
            ),
            Updates.combine(
                Updates.set("status", TaskStatus.RUNNING),
                Updates.set("claimed_by", podName),
                Updates.set("started_at", ZonedDateTime.now()),
                Updates.inc("retry_count", 1)
            )
        );

        if (updated == 0) {
            LOGGER.debug("task retry race lost, taskId={}", taskId);
            return false;
        }

        record.status = TaskStatus.RUNNING;
        record.claimedBy = podName;
        record.startedAt = ZonedDateTime.now();
        record.retryCount = (record.retryCount != null ? record.retryCount : 0) + 1;
        record.logs = null; // fresh logs on retry

        executeAndUpdate(task, taskId, record, record.retryCount);
        return true;
    }

    /**
     * List tasks, ordered by id descending (newest first), with optional type filter.
     */
    public List<BackgroundTask> list(String type, int limit) {
        var query = new Query();
        if (type != null && !type.isBlank()) {
            query.filter = Filters.eq("type", type);
        }
        query.sort = com.mongodb.client.model.Sorts.descending("_id");
        query.limit = Math.min(limit, 100);
        return taskCollection.find(query);
    }

    private void executeAndUpdate(AbstractTask task, String taskId, BackgroundTask record, int retryCount) {
        var ctx = new TaskContext();
        ctx.setDate(parseDate(taskId));
        try {
            task.execute(ctx);
            record.status = TaskStatus.SUCCESS;
            record.statusText = ctx.statusText();
            record.retryCount = retryCount;
            LOGGER.info("task completed successfully, taskId={}, type={}", taskId, task.type());
        } catch (Exception e) {
            ctx.log("ERROR: " + e.getMessage());
            record.status = TaskStatus.FAILED;
            record.statusText = ctx.statusText();
            record.retryCount = retryCount;
            LOGGER.error("task failed, taskId={}, type={}", taskId, task.type(), e);
        }

        record.completedAt = ZonedDateTime.now();
        record.logs = ctx.logs();
        taskCollection.replace(record);
    }

    private static String resolvePodName() {
        var env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) return env;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    /**
     * Parse a date suffix from a task ID of the form {@code TYPE:YYYY-MM-DD}.
     * Returns {@code null} if the suffix is absent or unparseable.
     */
    static LocalDate parseDate(String taskId) {
        int idx = taskId.lastIndexOf(':');
        if (idx <= 0 || idx >= taskId.length() - 1) return null;
        try {
            return LocalDate.parse(taskId.substring(idx + 1));
        } catch (Exception e) {
            return null;
        }
    }
}
