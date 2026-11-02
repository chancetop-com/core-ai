package ai.core.cli.appserver;

import ai.core.session.SessionLock;
import ai.core.session.TurnPersistence;
import ai.core.session.transcript.TranscriptStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Closing, deleting and idle-evicting live sessions, including their sidecars and locks. */
final class SessionLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionLifecycle.class);
    private static final long IDLE_TIMEOUT_MILLIS = 30L * 60L * 1000L;

    static Path stagingDir(String sessionId) {
        return Path.of(System.getProperty("user.home"), ".core-ai", "app-server", "staging", sessionId);
    }

    private static void saveQuietly(EngineSession session) {
        try {
            session.save();
        } catch (RuntimeException e) {
            LOGGER.warn("failed to save session {}: {}", session.id(), e.getMessage());
        }
    }

    private static void closeQuietly(EngineSession session) {
        try {
            session.close();
        } catch (RuntimeException e) {
            LOGGER.warn("failed to close session {}: {}", session.id(), e.getMessage());
        }
    }

    static RpcException sessionBusy(SessionLock.AcquireResult.Busy busy) {
        var holder = busy.holder();
        var description = holder == null ? "another process" : holder.describe();
        return RpcException.business("SESSION_BUSY", "session is in use by " + description);
    }

    private final EngineBootstrap bootstrap;
    private final Map<String, EngineSession> sessions;
    private final SessionSidecars sidecars;
    private final Supplier<EngineScheduler> scheduler;
    private final Supplier<NotificationSink> sinkSupplier;

    SessionLifecycle(EngineBootstrap bootstrap, Map<String, EngineSession> sessions, SessionSidecars sidecars,
                     Supplier<EngineScheduler> scheduler, Supplier<NotificationSink> sinkSupplier) {
        this.bootstrap = bootstrap;
        this.sessions = sessions;
        this.sidecars = sidecars;
        this.scheduler = scheduler;
        this.sinkSupplier = sinkSupplier;
    }

    ObjectNode close(ObjectNode params) {
        var id = SessionIds.check(Params.requiredText(params, "sessionId"));
        var session = sessions.remove(id);
        if (session != null) {
            try {
                session.save();
            } finally {
                session.close();
            }
        }
        var node = Params.object();
        node.put("sessionId", id);
        node.put("closed", session != null);
        return node;
    }

    @SuppressWarnings("PMD.UseTryWithResources")
    ObjectNode delete(ObjectNode params) {
        var ids = Params.stringArray(params, "sessionIds");
        for (var rawId : ids) {
            var id = SessionIds.check(rawId);
            var live = sessions.remove(id);
            if (live != null) {
                live.close();
            }
            var acquire = SessionLock.tryAcquire(bootstrap.sessionsDir, id, "engine");
            if (acquire instanceof SessionLock.AcquireResult.Busy busy) throw sessionBusy(busy);
            var lock = ((SessionLock.AcquireResult.Acquired) acquire).lock();
            try {
                sidecars.titles().remove(id);
                sidecars.models().remove(id);
                sidecars.titleResolver().invalidate(id);
                TurnPersistence.clear(id);
                var removedTasks = bootstrap.scheduledTaskStore.list(id);
                if (!removedTasks.isEmpty()) {
                    bootstrap.scheduledTaskStore.deleteBySession(id);
                    removedTasks.forEach(task -> EngineScheduler.sendChangedNotification(sinkSupplier.get(), task.id, "session_deleted"));
                }
                if (sessionFileExists(id)) {
                    bootstrap.sessionPersistence.delete(List.of(id));
                }
                TranscriptStore.delete(bootstrap.sessionsDir, id);
                Housekeeping.deleteDir(stagingDir(id));
            } finally {
                lock.close();
                removeLockFile(id);
            }
        }
        var activeScheduler = scheduler.get();
        if (activeScheduler != null) activeScheduler.refreshSnapshot();
        return Params.object();
    }

    void evictIdle() {
        long cutoff = System.currentTimeMillis() - IDLE_TIMEOUT_MILLIS;
        for (var entry : sessions.entrySet()) {
            var session = entry.getValue();
            if (!session.idle(cutoff)) continue;
            if (!sessions.remove(entry.getKey(), session)) continue;
            saveQuietly(session);
            closeQuietly(session);
        }
    }

    void closeAll() {
        for (var session : sessions.values()) {
            saveQuietly(session);
            closeQuietly(session);
        }
        sessions.clear();
    }

    private void removeLockFile(String id) {
        try {
            Files.deleteIfExists(bootstrap.sessionsDir.resolve(id + ".lock"));
        } catch (IOException e) {
            LOGGER.debug("failed to remove session lock file: {}", id, e);
        }
    }

    private boolean sessionFileExists(String id) {
        return bootstrap.sessionPersistence.exists(id);
    }
}
