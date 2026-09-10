package ai.core.server.asynctask;

import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.tool.ToolRegistryService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallAsyncTask;
import ai.core.tool.ToolCallAsyncTaskManager;
import ai.core.tool.ToolCallResult;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * The one place long-running tool calls live on the server. Every {@code pending} result any tool
 * returns (clip renders, assembly, enhancement, post-process, video generation) is stored by the
 * executor through {@link #manager()}, refreshed here on a fixed rate, and — when it turns terminal —
 * handed to the issuing session as a task notification, exactly like a background agent finishing.
 * Domain queues (render, assembly) keep their own dispatchers; this layer is about the tool call's
 * lifecycle, not about doing the work.
 * <p>
 * Delivery goes through the session command bus and is acknowledged, not fire-and-forget: a task whose
 * notification never reached its session stays on the books and is re-announced on later ticks. A
 * terminal task is never polled again, so a dropped notification here is a conversation that silently
 * stops forever.
 *
 * @author stephen
 */
public class AsyncToolTaskService {
    static final Duration TERMINAL_RETENTION = Duration.ofHours(24);
    static final int MAX_NOTIFY_ATTEMPTS = 10;
    static final Duration NOTIFY_RETRY_BASE_BACKOFF = Duration.ofSeconds(30);
    static final Duration NOTIFY_RETRY_MAX_BACKOFF = Duration.ofMinutes(10);
    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncToolTaskService.class);
    private static final int MAX_NOTIFICATION_RESULT_LENGTH = 30 * 1024;

    @Inject
    MongoAsyncTaskPersistence persistence;
    @Inject
    ToolRegistryService toolRegistryService;
    @Inject
    SessionOwnershipRegistry ownershipRegistry;

    private volatile ToolCallAsyncTaskManager manager;
    private volatile TaskNotificationDispatcher dispatcher =
        (sessionId, taskId, toolName, status, xml) -> TaskNotificationDispatcher.Outcome.RETRY;

    public ToolCallAsyncTaskManager manager() {
        var current = manager;
        if (current == null) {
            synchronized (this) {
                if (manager == null) {
                    var created = new ServerAsyncTaskManager(persistence, toolRegistryService);
                    created.addTerminalListener(this::notifySession);
                    manager = created;
                }
                current = manager;
            }
        }
        return current;
    }

    /** Wired after the command bus exists (it loads later than this module). */
    public void setNotificationDispatcher(TaskNotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * One tick: refresh every open task through its tool, re-announce the finished ones whose session
     * never took the notification, then drop the long-finished.
     */
    public void pollOpenTasks() {
        var current = manager();
        for (var task : current.listTasks()) {
            if (ownedElsewhere(task.sessionId())) continue;   // the owning replica drives its own sessions
            try {
                if (task.open()) {
                    current.pollTask(task.taskId());
                } else if (task.notificationPending()) {
                    retryNotification(current, task);
                }
            } catch (Exception e) {
                LOGGER.warn("async task tick failed, taskId={}", task.taskId(), e);
            }
        }
        current.purgeTerminalOlderThan(TERMINAL_RETENTION);
    }

    /** Called by the command handler once the owning session has taken the notification. */
    public boolean claimNotificationDelivery(String taskId) {
        return manager().markNotificationDelivered(taskId);
    }

    /** Called by the command handler when the session refused it after the claim, so the sweep retries. */
    public void releaseNotificationDelivery(String taskId) {
        manager().reopenNotification(taskId);
    }

    private void retryNotification(ToolCallAsyncTaskManager current, ToolCallAsyncTaskManager.TaskSnapshot task) {
        if (task.notifyAttempts() >= MAX_NOTIFY_ATTEMPTS) {
            current.abandonNotification(task.taskId());
            return;
        }
        var last = task.lastNotifyAttemptAtMs();
        if (last != null && System.currentTimeMillis() - last < backoffMs(task.notifyAttempts())) return;
        current.retryNotification(task.taskId());
    }

    /** Doubling backoff so a session that is down for a while is not re-announced at the poll rate. */
    private long backoffMs(int attempts) {
        var scaled = NOTIFY_RETRY_BASE_BACKOFF.toMillis() << Math.min(attempts, 8);
        return Math.min(scaled, NOTIFY_RETRY_MAX_BACKOFF.toMillis());
    }

    /**
     * Every replica runs this job, so without an owner check they would all poll the same task and
     * announce it more than once. An unowned session is still fair game: whoever publishes the command
     * claims it, exactly like {@code CommandPublisher} does for a user message.
     */
    private boolean ownedElsewhere(String sessionId) {
        if (sessionId == null || ownershipRegistry == null) return false;
        var owner = ownershipRegistry.getOwner(sessionId);
        return owner != null && !owner.equals(ownershipRegistry.getHostname());
    }

    void notifySession(String sessionId, ToolCallAsyncTask task, ToolCallResult result) {
        if (sessionId == null) return;
        var status = result.isCompleted() ? "completed" : "failed";
        var toolName = task.tool().getName();
        var outcome = dispatcher.dispatch(sessionId, task.taskId(), toolName, status, notificationXml(task, status, result));
        switch (outcome) {
            case DISPATCHED -> LOGGER.info("async task notification dispatched, taskId={}, tool={}, session={}, status={}",
                task.taskId(), toolName, sessionId, status);
            case ABANDONED -> manager().abandonNotification(task.taskId());
            case RETRY -> LOGGER.info("async task notification not accepted, will retry, taskId={}, session={}", task.taskId(), sessionId);
            default -> throw new IllegalStateException("unknown notification outcome: " + outcome);
        }
    }

    private String notificationXml(ToolCallAsyncTask task, String status, ToolCallResult result) {
        var body = truncate(result.getResult());
        var payload = "completed".equals(status) ? "<result>" + body + "</result>" : "<error>" + body + "</error>";
        return "<task-notification>%n<task-id>%s</task-id>%n<tool>%s</tool>%n<status>%s</status>%n%s%n</task-notification>%n"
            .formatted(task.taskId(), task.tool().getName(), status, payload);
    }

    private String truncate(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_NOTIFICATION_RESULT_LENGTH) return text;
        return text.substring(0, MAX_NOTIFICATION_RESULT_LENGTH) + "\n\n[Output truncated: showing first " + MAX_NOTIFICATION_RESULT_LENGTH + " characters]";
    }

    /** Falls back to the server tool catalogue, so a task survives even when no tool was registered by hand. */
    static final class ServerAsyncTaskManager extends ToolCallAsyncTaskManager {
        private final ToolRegistryService toolRegistryService;

        ServerAsyncTaskManager(MongoAsyncTaskPersistence persistence, ToolRegistryService toolRegistryService) {
            super(persistence);
            this.toolRegistryService = toolRegistryService;
        }

        @Override
        protected ToolCall resolveTool(String toolName) {
            var registered = super.resolveTool(toolName);
            return registered != null ? registered : toolRegistryService.findBuiltinTool(toolName);
        }
    }
}
