package ai.core.cli.schedule;

import ai.core.schedule.CronExpression;
import ai.core.schedule.ScheduledTask;
import ai.core.schedule.ScheduledTaskRun;
import ai.core.schedule.ScheduledTaskStore;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * File-backed {@link ScheduledTaskStore} for the CLI and the desktop engine. All tasks live in a
 * single JSON file ({@code ~/.core-ai/schedules.json} by default) and their run history in a
 * sibling {@code schedule-runs.json}; sessions are restored on resume because the store is keyed
 * by session id, not by process lifetime.
 *
 * <p>Several processes share the file (every workspace engine plus terminal CLI sessions), so
 * reads reload whenever the file changes and writes serialize on {@code schedules.lock} with a
 * reload-modify-write inside the lock — a stale in-memory snapshot can never overwrite another
 * process's tasks. A corrupt file degrades to a warning instead of blocking engine startup.</p>
 *
 * @author stephen
 */
public class FileScheduledTaskStore implements ScheduledTaskStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileScheduledTaskStore.class);
    private static final int MAX_RUNS_PER_TASK = 50;
    private static final long LOCK_TIMEOUT_MILLIS = 2_000L;
    private static final long LOCK_RETRY_MILLIS = 50L;

    private static ScheduledTask copyOf(ScheduledTask task) {
        var copy = new ScheduledTask();
        copy.id = task.id;
        copy.sessionId = task.sessionId;
        copy.userId = task.userId;
        copy.name = task.name;
        copy.cronExpression = task.cronExpression;
        copy.timezone = task.timezone;
        copy.input = task.input;
        copy.enabled = task.enabled;
        copy.nextRunAt = task.nextRunAt;
        copy.createdAt = task.createdAt;
        copy.updatedAt = task.updatedAt;
        copy.workspace = task.workspace;
        copy.lastRunAt = task.lastRunAt;
        copy.lastRunStatus = task.lastRunStatus;
        copy.runCount = task.runCount;
        copy.disabledReason = task.disabledReason;
        return copy;
    }

    private static void validate(String cronExpression, String timezone) {
        new CronExpression(cronExpression);
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("invalid timezone: " + timezone, e);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the schedules lock", e);
        }
    }

    private static FileStamp stampOrNull(Path path) {
        try {
            return FileStamp.of(path);
        } catch (IOException e) {
            return null;
        }
    }

    private final Path file;
    private final Path runsFile;
    private final Path lockFile;
    private final Path workspace;
    private final Object monitor = new Object();
    private volatile Map<String, ScheduledTask> tasks = Map.of();
    private volatile Map<String, List<ScheduledTaskRun>> runs = Map.of();
    private volatile FileStamp tasksStamp;
    private volatile FileStamp runsStamp;

    public FileScheduledTaskStore(Path file) {
        this(file, null);
    }

    /**
     * @param workspace owning workspace (absolute) stamped on newly created tasks; null keeps the
     *                  task unstamped (tests, tools that do not know their workspace)
     */
    public FileScheduledTaskStore(Path file, Path workspace) {
        this.file = file.toAbsolutePath();
        this.runsFile = this.file.resolveSibling("schedule-runs.json");
        this.lockFile = this.file.resolveSibling("schedules.lock");
        this.workspace = workspace == null ? null : workspace.toAbsolutePath().normalize();
    }

    @Override
    public ScheduledTask create(ScheduledTask task) {
        return writeLocked(() -> {
            reloadTasks();
            var copy = copyOf(task);
            if (copy.workspace == null && workspace != null) copy.workspace = workspace.toString();
            var now = ZonedDateTime.now();
            copy.nextRunAt = new CronExpression(copy.cronExpression).nextAfter(now, ZoneId.of(copy.timezone));
            copy.createdAt = now;
            copy.updatedAt = now;
            var next = new LinkedHashMap<>(tasks);
            next.put(copy.id, copy);
            tasks = next;
            persistTasks(next);
            return copy;
        });
    }

    @Override
    public ScheduledTask update(ScheduledTask task) {
        if (task == null || task.id == null) {
            throw new IllegalArgumentException("scheduled task id is required for update");
        }
        return writeLocked(() -> {
            reloadTasks();
            var existing = tasks.get(task.id);
            if (existing == null) {
                throw new IllegalArgumentException("scheduled task not found, id=" + task.id);
            }
            var merged = copyOf(existing);
            if (task.name != null) merged.name = task.name;
            if (task.cronExpression != null) merged.cronExpression = task.cronExpression;
            if (task.timezone != null) merged.timezone = task.timezone;
            if (task.input != null) merged.input = task.input;
            if (task.enabled != null) merged.enabled = task.enabled;
            if (task.disabledReason != null) merged.disabledReason = task.disabledReason;
            if (Boolean.TRUE.equals(merged.enabled)) merged.disabledReason = null;
            if (merged.workspace == null && task.workspace != null) merged.workspace = task.workspace;
            validate(merged.cronExpression, merged.timezone);
            var recompute = !Objects.equals(existing.cronExpression, merged.cronExpression)
                    || !Objects.equals(existing.timezone, merged.timezone)
                    || Boolean.TRUE.equals(merged.enabled) && !Boolean.TRUE.equals(existing.enabled);
            if (recompute) {
                merged.nextRunAt = new CronExpression(merged.cronExpression).nextAfter(ZonedDateTime.now(), ZoneId.of(merged.timezone));
            }
            merged.updatedAt = ZonedDateTime.now();
            var next = new LinkedHashMap<>(tasks);
            next.put(merged.id, merged);
            tasks = next;
            persistTasks(next);
            return merged;
        });
    }

    @Override
    public boolean delete(String id) {
        return writeLocked(() -> {
            reloadTasks();
            if (!tasks.containsKey(id)) return Boolean.FALSE;
            var next = new LinkedHashMap<>(tasks);
            next.remove(id);
            tasks = next;
            persistTasks(next);
            reloadRuns();
            if (runs.containsKey(id)) {
                var nextRuns = new LinkedHashMap<>(runs);
                nextRuns.remove(id);
                runs = nextRuns;
                persistRuns(nextRuns);
            }
            return Boolean.TRUE;
        });
    }

    @Override
    public int deleteBySession(String sessionId) {
        return writeLocked(() -> {
            reloadTasks();
            var ids = new ArrayList<String>();
            for (var task : tasks.values()) {
                if (Objects.equals(task.sessionId, sessionId) && task.id != null) ids.add(task.id);
            }
            if (ids.isEmpty()) return 0;
            var next = new LinkedHashMap<>(tasks);
            ids.forEach(next::remove);
            tasks = next;
            persistTasks(next);
            reloadRuns();
            var nextRuns = new LinkedHashMap<>(runs);
            var runsChanged = nextRuns.keySet().removeAll(ids);
            if (runsChanged) {
                runs = nextRuns;
                persistRuns(nextRuns);
            }
            return ids.size();
        });
    }

    @Override
    public boolean claim(String id, ZonedDateTime expectedNextRunAt, ZonedDateTime newNextRunAt) {
        return writeLocked(() -> {
            reloadTasks();
            var task = tasks.get(id);
            if (task == null || task.nextRunAt == null || !task.nextRunAt.isEqual(expectedNextRunAt)) return Boolean.FALSE;
            task.nextRunAt = newNextRunAt;
            persistTasks(tasks);
            return Boolean.TRUE;
        });
    }

    @Override
    public void recordRun(ScheduledTaskRun run) {
        writeLocked(() -> {
            reloadTasks();
            var task = tasks.get(run.taskId);
            if (task != null) {
                task.lastRunAt = run.firedAt;
                task.lastRunStatus = run.status;
                task.runCount = (task.runCount == null ? 0 : task.runCount) + 1;
                persistTasks(tasks);
            }
            reloadRuns();
            var list = new ArrayList<>(runs.getOrDefault(run.taskId, List.of()));
            list.add(0, run);
            while (list.size() > MAX_RUNS_PER_TASK) {
                list.remove(list.size() - 1);
            }
            var nextRuns = new LinkedHashMap<>(runs);
            nextRuns.put(run.taskId, list);
            runs = nextRuns;
            persistRuns(nextRuns);
            return null;
        });
    }

    @Override
    public List<ScheduledTask> list(String sessionId) {
        reloadTasks();
        return tasks.values().stream()
                .filter(task -> Objects.equals(task.sessionId, sessionId))
                .toList();
    }

    @Override
    public List<ScheduledTask> listAll() {
        reloadTasks();
        return List.copyOf(tasks.values());
    }

    @Override
    public ScheduledTask get(String id) {
        reloadTasks();
        return tasks.get(id);
    }

    @Override
    public List<ScheduledTask> findDue(ZonedDateTime now) {
        reloadTasks();
        return tasks.values().stream()
                .filter(task -> task.nextRunAt != null
                        && !task.nextRunAt.isAfter(now)
                        && Boolean.TRUE.equals(task.enabled))
                .toList();
    }

    @Override
    public List<ScheduledTaskRun> runs(String taskId, int limit) {
        reloadRuns();
        var list = runs.get(taskId);
        if (list == null || list.isEmpty() || limit <= 0) return List.of();
        return List.copyOf(list.size() <= limit ? list : list.subList(0, limit));
    }

    private <T> T writeLocked(Supplier<T> action) {
        synchronized (monitor) {
            createParentDirectories();
            return withFileLock(action);
        }
    }

    private <T> T withFileLock(Supplier<T> action) {
        try (var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            acquireLock(channel);
            // Closing the channel releases the lock; every mutation runs while it is held.
            return action.get();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to lock scheduled tasks, file=" + lockFile, e);
        }
    }

    private void createParentDirectories() {
        try {
            var parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create schedules directory, file=" + file, e);
        }
    }

    private void acquireLock(FileChannel channel) throws IOException {
        var deadline = System.currentTimeMillis() + LOCK_TIMEOUT_MILLIS;
        while (true) {
            if (channel.tryLock() != null) return;
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException("timed out waiting for the schedules lock, file=" + lockFile);
            }
            sleepQuietly(LOCK_RETRY_MILLIS);
        }
    }

    private void reloadTasks() {
        var stamp = stampOrNull(file);
        if (stamp == null || stamp.equals(tasksStamp)) return;
        try {
            tasks = readTasks();
        } catch (RuntimeException e) {
            // keep the last good snapshot; a hand-edited file must never take the engine down
            LOGGER.warn("failed to parse scheduled tasks, file={}: {}", file, e.getMessage());
        }
        tasksStamp = stamp;
    }

    private void reloadRuns() {
        var stamp = stampOrNull(runsFile);
        if (stamp == null || stamp.equals(runsStamp)) return;
        try {
            runs = readRuns();
        } catch (RuntimeException e) {
            LOGGER.warn("failed to parse scheduled runs, file={}: {}", runsFile, e.getMessage());
        }
        runsStamp = stamp;
    }

    private Map<String, ScheduledTask> readTasks() {
        var loaded = new LinkedHashMap<String, ScheduledTask>();
        if (!Files.exists(file)) return loaded;
        try {
            var content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.isBlank()) return loaded;
            var parsed = JsonUtil.fromJson(new TypeReference<List<ScheduledTask>>() {
            }, content);
            if (parsed != null) {
                for (var task : parsed) {
                    if (task.id != null) loaded.put(task.id, task);
                }
            }
            return loaded;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read scheduled tasks, file=" + file, e);
        }
    }

    private Map<String, List<ScheduledTaskRun>> readRuns() {
        var loaded = new LinkedHashMap<String, List<ScheduledTaskRun>>();
        if (!Files.exists(runsFile)) return loaded;
        try {
            var content = Files.readString(runsFile, StandardCharsets.UTF_8);
            if (content.isBlank()) return loaded;
            var parsed = JsonUtil.fromJson(new TypeReference<Map<String, List<ScheduledTaskRun>>>() {
            }, content);
            if (parsed != null) {
                for (var entry : parsed.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        loaded.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                    }
                }
            }
            return loaded;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read scheduled runs, file=" + runsFile, e);
        }
    }

    private void persistTasks(Map<String, ScheduledTask> snapshot) {
        persist(file, JsonUtil.toJson(new ArrayList<>(snapshot.values())), "scheduled tasks");
        tasksStamp = stampOrNull(file);
    }

    private void persistRuns(Map<String, List<ScheduledTaskRun>> snapshot) {
        persist(runsFile, JsonUtil.toJson(snapshot), "scheduled runs");
        runsStamp = stampOrNull(runsFile);
    }

    private void persist(Path target, String content, String what) {
        try {
            var parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            var tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            moveInto(tmp, target);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist " + what + ", file=" + target, e);
        }
    }

    private void moveInto(Path tmp, Path target) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                // Windows sharing violations from a concurrent reader are transient; retry briefly.
                if (attempt >= 2) throw e;
                sleepQuietly(LOCK_RETRY_MILLIS);
            }
        }
    }

    /**
     * mtime+size fingerprint of a file; {@code (-1, -1)} when it does not exist.
     */
    private record FileStamp(long modifiedAtMillis, long size) {
        static FileStamp of(Path path) throws IOException {
            if (!Files.exists(path)) return new FileStamp(-1, -1);
            return new FileStamp(Files.getLastModifiedTime(path).toMillis(), Files.size(path));
        }
    }
}
