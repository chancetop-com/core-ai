package ai.core.cli.appserver;

import ai.core.cli.utils.PathUtils;
import ai.core.schedule.CronExpression;
import ai.core.schedule.ScheduledTask;
import ai.core.schedule.ScheduledTaskRun;
import ai.core.schedule.ScheduledTaskStore;
import ai.core.tool.tools.ScheduledTaskTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Fires the scheduled tasks owned by this engine: every 30s it reloads the shared store, filters
 * tasks down to this workspace, claims each due occurrence (a cross-process CAS in the file
 * store), rebuilds the session quietly when it is not in memory and injects the trigger message.
 * The desktop never runs tasks itself — it keeps this engine alive around the due time.
 *
 * <p>Task definition changes made outside this engine (the agent tool, a terminal CLI, another
 * workspace engine) are detected by diffing a snapshot and announced as {@code schedule/changed};
 * fires are announced as {@code schedule/fired}.</p>
 *
 * @author stephen
 */
public class EngineScheduler implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(EngineScheduler.class);
    private static final long INITIAL_DELAY_SECONDS = 5L;
    private static final long TICK_PERIOD_SECONDS = 30L;

    static void sendChangedNotification(NotificationSink sink, String id, String reason) {
        if (sink == null) return;
        var params = Params.object();
        params.put("id", id);
        params.put("reason", reason);
        sink.notify("schedule/changed", params);
    }

    private static String changedReason(SnapshotEntry before, boolean enabled, ScheduledTask task) {
        // simple checks first: SEO (fb-contrib) flags method calls evaluated before plain locals
        if (!enabled && task.disabledReason != null && before.enabled()) return "auto_disabled";
        return "updated";
    }

    private static Map<String, SnapshotEntry> snapshotOf(List<ScheduledTask> tasks) {
        var result = new LinkedHashMap<String, SnapshotEntry>();
        for (var task : tasks) {
            if (task.id != null) {
                result.put(task.id, new SnapshotEntry(Boolean.TRUE.equals(task.enabled), task.updatedAt));
            }
        }
        return result;
    }

    private final ScheduledTaskStore store;
    private final Path workspace;
    private final Sessions sessions;
    private final Supplier<NotificationSink> sinkSupplier;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "engine-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private final Object snapshotLock = new Object();
    private volatile Map<String, SnapshotEntry> snapshot;

    public EngineScheduler(EngineBootstrap bootstrap, EngineSessionRegistry registry, Supplier<NotificationSink> sink) {
        this(bootstrap.scheduledTaskStore, bootstrap.workspace, new RegistrySessions(registry), sink);
    }

    EngineScheduler(ScheduledTaskStore store, Path workspace, Sessions sessions, Supplier<NotificationSink> sink) {
        this.store = store;
        this.workspace = workspace.toAbsolutePath().normalize();
        this.sessions = sessions;
        this.sinkSupplier = sink;
        this.snapshot = snapshotOf(store.listAll());
    }

    void start() {
        ticker.scheduleWithFixedDelay(this::tickQuietly, INITIAL_DELAY_SECONDS, TICK_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        ticker.shutdownNow();
    }

    void tick() {
        var tasks = store.listAll();
        notifyChanged(tasks);
        var now = ZonedDateTime.now();
        for (var task : store.findDue(now)) {
            try {
                if (!owns(task)) continue;
                // claim mutates the task in place, so capture the due time before advancing it
                var scheduledFor = task.nextRunAt;
                var next = new CronExpression(task.cronExpression).nextAfter(now, ZoneId.of(task.timezone));
                if (!store.claim(task.id, scheduledFor, next)) continue;
                backfillWorkspace(task);
                fire(task, scheduledFor, false);
            } catch (RuntimeException e) {
                LOGGER.warn("failed to fire scheduled task, id={}", task.id, e);
            }
        }
        refreshSnapshot();
    }

    /**
     * Schedules/trigger implementation: manual runs ignore {@code enabled}, never claim and never
     * touch {@code nextRunAt}; a missing session is an error instead of an automatic disable.
     */
    ScheduledTaskRun triggerNow(String taskId) {
        var task = store.get(taskId);
        if (task == null) {
            throw RpcException.business("TASK_NOT_FOUND", "scheduled task not found: " + taskId, "id", taskId);
        }
        if (!owns(task)) {
            throw RpcException.business("WRONG_WORKSPACE", "task belongs to another workspace: " + task.workspace,
                    "workspace", task.workspace);
        }
        var session = resolveSession(task.sessionId);
        if (session == null) {
            throw RpcException.business("SESSION_NOT_FOUND", "session not found: " + task.sessionId,
                    "sessionId", task.sessionId);
        }
        var run = sendRun(task, session, null, true);
        refreshSnapshot();
        return run;
    }

    boolean owns(ScheduledTask task) {
        if (task.workspace != null) {
            try {
                return workspace.equals(Path.of(task.workspace).toAbsolutePath().normalize());
            } catch (RuntimeException e) {
                return false;
            }
        }
        return task.sessionId != null && Files.exists(sessionFile(task.sessionId));
    }

    Path sessionFile(String sessionId) {
        return Path.of(PathUtils.sessionsDir(workspace), sessionId + ".data");
    }

    void refreshSnapshot() {
        synchronized (snapshotLock) {
            snapshot = snapshotOf(store.listAll());
        }
    }

    private void tickQuietly() {
        try {
            tick();
        } catch (RuntimeException e) {
            LOGGER.warn("schedule tick failed", e);
        }
    }

    private void fire(ScheduledTask task, ZonedDateTime scheduledFor, boolean manual) {
        var session = resolveSession(task.sessionId);
        if (session == null) {
            var run = startRun(task, scheduledFor, manual);
            run.status = "session_missing";
            store.recordRun(run);
            autoDisable(task);
            notifyFired(task, run);
            return;
        }
        sendRun(task, session, scheduledFor, manual);
    }

    private ScheduledTaskRun sendRun(ScheduledTask task, Session session, ZonedDateTime scheduledFor, boolean manual) {
        var run = startRun(task, scheduledFor, manual);
        try {
            run.status = session.running() ? "queued" : "fired";
            session.send(ScheduledTaskTool.buildTriggerMessage(task));
            store.recordRun(run);
        } catch (RuntimeException e) {
            run.status = "failed";
            run.error = e.getMessage();
            recordFailedRun(run);
            LOGGER.warn("failed to fire scheduled task, id={}", task.id, e);
        }
        notifyFired(task, run);
        return run;
    }

    private void recordFailedRun(ScheduledTaskRun run) {
        try {
            store.recordRun(run);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to record scheduled run, taskId={}", run.taskId, e);
        }
    }

    private Session resolveSession(String sessionId) {
        if (sessionId == null) return null;
        var session = sessions.find(sessionId);
        return session != null ? session : sessions.resume(sessionId);
    }

    private ScheduledTaskRun startRun(ScheduledTask task, ZonedDateTime scheduledFor, boolean manual) {
        var run = new ScheduledTaskRun();
        run.id = UUID.randomUUID().toString();
        run.taskId = task.id;
        run.sessionId = task.sessionId;
        run.scheduledFor = scheduledFor;
        run.firedAt = ZonedDateTime.now();
        run.lateMillis = scheduledFor == null ? null : Duration.between(scheduledFor, run.firedAt).toMillis();
        run.manual = manual;
        return run;
    }

    private void autoDisable(ScheduledTask task) {
        try {
            var patch = new ScheduledTask();
            patch.id = task.id;
            patch.enabled = Boolean.FALSE;
            patch.disabledReason = "session_missing";
            store.update(patch);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to auto-disable scheduled task, id={}", task.id, e);
        }
    }

    private void backfillWorkspace(ScheduledTask task) {
        if (task.workspace != null) return;
        try {
            var patch = new ScheduledTask();
            patch.id = task.id;
            patch.workspace = workspace.toString();
            store.update(patch);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to backfill scheduled task workspace, id={}", task.id, e);
        }
    }

    private void notifyChanged(List<ScheduledTask> tasks) {
        synchronized (snapshotLock) {
            var current = snapshotOf(tasks);
            for (var task : tasks) {
                var before = snapshot.get(task.id);
                var enabled = Boolean.TRUE.equals(task.enabled);
                if (before == null) {
                    sendChangedNotification(sinkSupplier.get(), task.id, "created");
                } else if (before.enabled() != enabled || !Objects.equals(before.updatedAt(), task.updatedAt)) {
                    sendChangedNotification(sinkSupplier.get(), task.id, changedReason(before, enabled, task));
                }
            }
            for (var id : snapshot.keySet()) {
                if (!current.containsKey(id)) sendChangedNotification(sinkSupplier.get(), id, "deleted");
            }
        }
    }

    private void notifyFired(ScheduledTask task, ScheduledTaskRun run) {
        var sink = sinkSupplier.get();
        if (sink == null) return;
        var params = Params.object();
        params.put("id", task.id);
        params.put("sessionId", task.sessionId);
        params.put("name", task.name);
        params.put("status", run.status);
        params.set("run", ScheduledTaskViews.run(run));
        sink.notify("schedule/fired", params);
    }

    /**
     * Test seam: the scheduler only needs to look a session up and push a message into it.
     */
    interface Session {
        boolean running();

        void send(String message);
    }

    interface Sessions {
        Session find(String sessionId);

        Session resume(String sessionId);
    }

    static final class RegistrySessions implements Sessions {
        private final EngineSessionRegistry registry;

        RegistrySessions(EngineSessionRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Session find(String sessionId) {
            return wrap(registry.find(sessionId));
        }

        @Override
        public Session resume(String sessionId) {
            return wrap(registry.resumeQuietly(sessionId));
        }

        private Session wrap(EngineSession session) {
            return session == null ? null : new EngineSessionHandle(session);
        }
    }

    private static final class EngineSessionHandle implements Session {
        private final EngineSession session;

        EngineSessionHandle(EngineSession session) {
            this.session = session;
        }

        @Override
        public boolean running() {
            return session.running();
        }

        @Override
        public void send(String message) {
            session.inProcess().sendMessage(message, null, null);
        }
    }

    private record SnapshotEntry(boolean enabled, ZonedDateTime updatedAt) {
    }
}
