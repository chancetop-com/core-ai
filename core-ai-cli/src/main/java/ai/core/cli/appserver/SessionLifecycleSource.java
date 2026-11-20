package ai.core.cli.appserver;

import ai.core.session.InProcessAgentSession;
import ai.core.session.lifecycle.TaskRunEnded;
import ai.core.session.lifecycle.TaskRunObserver;
import ai.core.session.lifecycle.TaskRunSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Authoritative Task Run facts for one live session, plus the durable journal underneath. Desktop
 * holds the Watch and the outbound promise; this source only records what actually happened and
 * lets an already-known run be bound or replayed after a restart.
 *
 * @author stephen
 */
final class SessionLifecycleSource implements TaskRunObserver, AutoCloseable {
    private static final long DEFAULT_BIND_TTL_MILLIS = 24L * 60L * 60L * 1000L;
    private static final long MAX_BIND_TTL_MILLIS = 8L * 24L * 60L * 60L * 1000L;

    private static ObjectNode snapshotNode(TaskRunSnapshot snapshot) {
        var node = Params.object();
        node.put("sessionId", snapshot.sessionId());
        node.put("sessionIncarnation", snapshot.sessionIncarnation());
        node.put("taskRunId", snapshot.taskRunId());
        node.put("runGeneration", snapshot.runGeneration());
        node.put("version", snapshot.version());
        node.put("requestId", snapshot.requestId());
        node.put("state", snapshot.state().name().toLowerCase());
        if (snapshot.rootDisposition() != null) node.put("rootDisposition", snapshot.rootDisposition());
        if (snapshot.activeExecutionId() != null) node.put("activeExecutionId", snapshot.activeExecutionId());
        node.put("requiredJobsUnsettled", snapshot.requiredJobsUnsettled());
        node.put("pendingContinuations", snapshot.pendingContinuations());
        node.put("queuedContinuations", snapshot.queuedContinuations());
        node.put("queuedRequests", snapshot.queuedRequests());
        node.put("coverage", snapshot.coverage());
        if (snapshot.attentionReason() != null) node.put("attentionReason", snapshot.attentionReason());
        node.put("startedAtMs", snapshot.startedAtMs());
        if (snapshot.endedAtMs() != null) node.put("endedAtMs", snapshot.endedAtMs());
        if (snapshot.outcome() != null) node.put("outcome", snapshot.outcome().name().toLowerCase());
        if (snapshot.reason() != null) node.put("reason", snapshot.reason());
        return node;
    }

    private static ObjectNode endedNode(TaskRunEnded ended) {
        var node = Params.object();
        node.put("sessionId", ended.sessionId());
        node.put("sessionIncarnation", ended.sessionIncarnation());
        node.put("taskRunId", ended.taskRunId());
        node.put("runGeneration", ended.runGeneration());
        node.put("version", ended.version());
        node.put("outcome", ended.outcome().name().toLowerCase());
        node.put("reason", ended.reason());
        node.put("endedAtMs", ended.endedAtMs());
        node.put("durationMs", ended.durationMs());
        node.put("coverage", ended.coverage());
        node.put("externalStopState", ended.externalStopState());
        return node;
    }

    private static String signature(TaskRunSnapshot snapshot) {
        return snapshot.taskRunId() + "|" + snapshot.state() + "|" + snapshot.activeExecutionId() + "|"
                + snapshot.rootDisposition() + "|" + snapshot.requiredJobsUnsettled() + "|"
                + snapshot.pendingContinuations() + "|" + snapshot.queuedContinuations() + "|"
                + snapshot.queuedRequests() + "|" + snapshot.coverage() + "|" + snapshot.attentionReason();
    }

    private final String sessionId;
    private final InProcessAgentSession session;
    private final LifecycleJournal journal;
    private final Supplier<NotificationSink> sinkSupplier;
    private final Map<String, Lease> leases = new ConcurrentHashMap<>();
    private volatile boolean storageError;
    private volatile String storageErrorMessage;
    private volatile String lastStateSignature;

    SessionLifecycleSource(String sessionId, InProcessAgentSession session, Path sessionsDir,
                           Supplier<NotificationSink> sinkSupplier) {
        this.sessionId = sessionId;
        this.session = session;
        this.sinkSupplier = sinkSupplier;
        this.journal = LifecycleJournal.open(sessionsDir, sessionId);
        session.taskRuns().setSessionIncarnation(journal.incarnation());
        recoverUnfinishedRun();
        session.taskRuns().setObserver(this);
    }

    String sessionId() {
        return sessionId;
    }

    String incarnation() {
        return journal.incarnation();
    }

    boolean storageError() {
        return storageError;
    }

    private void recoverUnfinishedRun() {
        var run = journal.unfinishedRun();
        if (run == null) return;
        var now = System.currentTimeMillis();
        var startedAt = run.path("startedAtMs").asLong(now);
        var payload = Params.object();
        payload.set("run", run);
        var ended = Params.object();
        ended.put("sessionId", sessionId);
        ended.put("sessionIncarnation", journal.incarnation());
        ended.put("taskRunId", run.path("taskRunId").asText());
        ended.put("runGeneration", run.path("runGeneration").asLong());
        ended.put("outcome", "interrupted");
        ended.put("reason", "engine_restarted");
        ended.put("endedAtMs", now);
        ended.put("durationMs", Math.max(0, now - startedAt));
        ended.put("coverage", run.path("coverage").asText("unknown"));
        ended.put("externalStopState", "unknown");
        payload.set("ended", ended);
        try {
            journal.append("run_ended", payload);
        } catch (RuntimeException e) {
            markStorageError(e);
        }
    }

    @Override
    public void onTaskRunRegistered(TaskRunSnapshot snapshot) {
        persist(snapshot, "run_registered", null);
    }

    @Override
    public void onTaskRunChanged(TaskRunSnapshot snapshot) {
        var signature = signature(snapshot);
        if (signature.equals(lastStateSignature)) return;
        lastStateSignature = signature;
        persist(snapshot, "run_state", null);
    }

    @Override
    public void onTaskRunEnded(TaskRunSnapshot snapshot, TaskRunEnded ended) {
        persist(snapshot, "run_ended", ended);
    }

    private void persist(TaskRunSnapshot snapshot, String type, TaskRunEnded ended) {
        if (storageError) return;
        var payload = Params.object();
        payload.set("run", snapshotNode(snapshot));
        if (ended != null) payload.set("ended", endedNode(ended));
        try {
            var record = journal.append(type, payload);
            announce(record, type, snapshot, ended);
        } catch (RuntimeException e) {
            markStorageError(e);
        }
    }

    private void markStorageError(RuntimeException e) {
        storageError = true;
        storageErrorMessage = e.getMessage();
    }

    private void announce(JsonNode record, String journalType, TaskRunSnapshot snapshot, TaskRunEnded ended) {
        var sink = sinkSupplier.get();
        if (sink == null) return;
        var event = Params.object();
        event.put("event_id", journal.incarnation() + ":" + record.path("seq").asLong());
        event.put("lifecycle_seq", record.path("seq").asLong());
        event.put("session_incarnation", journal.incarnation());
        event.put("task_run_id", snapshot.taskRunId());
        event.put("run_generation", snapshot.runGeneration());
        event.put("run_version", snapshot.version());
        event.put("type", switch (journalType) {
            case "run_registered" -> "task_run.registered";
            case "run_state" -> "task_run.state_changed";
            case "run_ended" -> "task_run.ended";
            default -> journalType;
        });
        event.put("state", snapshot.state().name().toLowerCase());
        if (snapshot.rootDisposition() != null) event.put("root_disposition", snapshot.rootDisposition());
        if (snapshot.activeExecutionId() != null) event.put("execution_id", snapshot.activeExecutionId());
        event.put("required_jobs_unsettled", snapshot.requiredJobsUnsettled());
        event.put("pending_continuations", snapshot.pendingContinuations());
        event.put("queued_continuations", snapshot.queuedContinuations());
        event.put("coverage", snapshot.coverage());
        if (snapshot.attentionReason() != null) event.put("attention_reason", snapshot.attentionReason());
        if (ended != null) {
            event.put("outcome", ended.outcome().name().toLowerCase());
            event.put("reason", ended.reason());
            event.put("duration_ms", ended.durationMs());
            event.put("external_stop_state", ended.externalStopState());
        }
        var params = Params.object();
        params.put("sessionId", sessionId);
        params.set("event", event);
        sink.notify("session/lifecycle", params);
    }

    ObjectNode inspect() {
        var node = Params.object();
        node.put("sessionId", sessionId);
        node.put("sessionIncarnation", journal.incarnation());
        node.put("lifecycleSeq", journal.seq());
        node.put("runtimeState", session.isTurnRunning() ? "running" : "loaded");
        node.put("observationState", storageError ? "storage_error" : "authoritative");
        if (storageErrorMessage != null) node.put("observationError", storageErrorMessage);
        var active = session.taskRuns().activeSnapshot();
        if (active != null) {
            node.set("activeTaskRun", snapshotNode(active));
            node.put("coverage", active.coverage());
        } else {
            node.put("coverage", "complete");
        }
        var ended = session.taskRuns().lastEndedSnapshot();
        if (ended != null) {
            var last = Params.object();
            last.set("run", snapshotNode(ended));
            last.set("ended", snapshotNode(ended));
            node.set("lastEndedTaskRun", last);
        } else {
            var journalEnded = journal.lastEnded();
            if (journalEnded != null) node.set("lastEndedTaskRun", journalEnded);
        }
        node.putArray("queuedTaskRunRefs");
        return node;
    }

    ObjectNode bind(String watchId, String taskRunId, long runGeneration, long ttlMillis) {
        if (watchId == null || watchId.isBlank()) throw RpcException.invalidParams("watchId is required");
        if (taskRunId == null || taskRunId.isBlank()) throw RpcException.invalidParams("taskRunId is required");
        var active = session.taskRuns().activeSnapshot();
        if (active != null && taskRunId.equals(active.taskRunId()) && active.runGeneration() == runGeneration) {
            var ttl = ttlMillis > 0 ? Math.min(ttlMillis, MAX_BIND_TTL_MILLIS) : DEFAULT_BIND_TTL_MILLIS;
            var expiresAt = System.currentTimeMillis() + ttl;
            var lease = new Lease(watchId, taskRunId, runGeneration, expiresAt);
            if (!appendWatchBind(lease)) {
                throw RpcException.business("LIFECYCLE_STORAGE_ERROR", "could not persist the watch bind");
            }
            leases.put(watchId, lease);
            var node = Params.object();
            node.put("status", "bound");
            node.put("watchId", watchId);
            node.put("lifecycleSeq", journal.seq());
            node.set("run", snapshotNode(active));
            return node;
        }
        var ended = journal.endedForRun(taskRunId);
        if (ended != null) {
            var endedGeneration = ended.path("ended").path("runGeneration").asLong(0);
            if (runGeneration == 0 || endedGeneration == runGeneration) {
                var node = Params.object();
                node.put("status", "already_ended");
                node.put("watchId", watchId);
                node.set("lastEndedTaskRun", ended);
                return node;
            }
        }
        if (active != null) {
            throw RpcException.business("TASK_RUN_NOT_ACTIVE", "task run is not active: " + taskRunId,
                    "activeTaskRunId", active.taskRunId());
        }
        throw RpcException.business("TASK_RUN_NOT_FOUND", "task run not found: " + taskRunId);
    }

    private boolean appendWatchBind(Lease lease) {
        if (storageError) return false;
        var payload = Params.object();
        payload.put("watchId", lease.watchId());
        payload.put("taskRunId", lease.taskRunId());
        payload.put("runGeneration", lease.runGeneration());
        payload.put("expiresAt", lease.expiresAt());
        try {
            journal.append("watch_bind", payload);
            return true;
        } catch (RuntimeException e) {
            markStorageError(e);
            return false;
        }
    }

    ObjectNode bindStatus(String watchId) {
        var lease = leases.get(watchId);
        if (lease != null) {
            var node = Params.object();
            node.put("status", "bound");
            node.put("watchId", watchId);
            node.put("taskRunId", lease.taskRunId());
            node.put("runGeneration", lease.runGeneration());
            var active = session.taskRuns().activeSnapshot();
            if (active != null && lease.taskRunId().equals(active.taskRunId())) {
                node.set("run", snapshotNode(active));
            }
            return node;
        }
        var persisted = journal.watchBind(watchId);
        if (persisted != null) {
            var taskRunId = persisted.path("taskRunId").asText();
            var ended = journal.endedForRun(taskRunId);
            var node = Params.object();
            node.put("status", ended != null ? "already_ended" : "pending");
            node.put("watchId", watchId);
            node.put("taskRunId", taskRunId);
            node.put("runGeneration", persisted.path("runGeneration").asLong());
            node.put("lifecycleSeq", journal.seq());
            if (ended != null) node.set("lastEndedTaskRun", ended);
            return node;
        }
        var node = Params.object();
        node.put("status", storageError ? "storage_error" : "not_found");
        node.put("watchId", watchId);
        return node;
    }

    ObjectNode replay(String watchId, long afterSeq, int limit) {
        var bind = leases.get(watchId);
        String taskRunId = bind != null ? bind.taskRunId() : null;
        long bindSeq = 0;
        if (taskRunId == null) {
            var persisted = journal.watchBind(watchId);
            if (persisted == null) {
                throw RpcException.business("WATCH_NOT_BOUND", "watch is not bound: " + watchId);
            }
            taskRunId = persisted.path("taskRunId").asText();
            bindSeq = persisted.path("seq").asLong();
        }
        var boundedLimit = Math.max(1, Math.min(limit, 500));
        var records = journal.replayForRun(taskRunId, Math.max(afterSeq, bindSeq), boundedLimit);
        var node = Params.object();
        node.put("watchId", watchId);
        node.put("taskRunId", taskRunId);
        node.put("afterSeq", afterSeq);
        node.put("lifecycleSeq", journal.seq());
        var array = node.putArray("records");
        records.forEach(array::add);
        return node;
    }

    ObjectNode unbind(String watchId) {
        leases.remove(watchId);
        if (!storageError) {
            var payload = Params.object();
            payload.put("watchId", watchId);
            try {
                journal.append("watch_unbind", payload);
            } catch (RuntimeException e) {
                markStorageError(e);
            }
        }
        var node = Params.object();
        node.put("watchId", watchId);
        node.put("status", storageError ? "storage_error" : "unbound");
        return node;
    }

    List<String> activeLeases() {
        return leases.keySet().stream().sorted().toList();
    }

    @Override
    public void close() {
        journal.close();
    }

    private record Lease(String watchId, String taskRunId, long runGeneration, long expiresAt) {
    }
}
