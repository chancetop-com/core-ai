package ai.core.server.workflow;

import ai.core.server.domain.ArtifactRef;
import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.sandbox.SandboxService;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mongo lifecycle around the pure {@link WorkflowAdvancer} drive loop: claim a run with a run-level CAS lease
 * (the only cross-replica claim, mirroring AgentScheduler's CAS on next_run_at), keep the lease alive with an
 * independent heartbeat (never gated on node completion, and tolerant of a transient Mongo blip), drive it,
 * then settle it with a status-guarded conditional update. One run is owned whole by one replica; parallel
 * nodes run in this worker's pool.
 *
 * @author Xander
 */
public class WorkflowRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowRunner.class);
    private static final int MAX_CONCURRENT_NODES = 8;
    private static final int MAX_CONCURRENT_RUNS = 16;
    private static final int LEASE_SECONDS = 60;
    private static final int HEARTBEAT_PERIOD_SECONDS = LEASE_SECONDS / 3;
    private static final int HEARTBEAT_THREADS = 4;   // a blocked renew on one run must not starve the others

    private final ExecutorService nodePool = Executors.newFixedThreadPool(MAX_CONCURRENT_NODES);
    private final ExecutorService driverPool = Executors.newFixedThreadPool(MAX_CONCURRENT_RUNS);
    private final ScheduledExecutorService heartbeatScheduler = Executors.newScheduledThreadPool(HEARTBEAT_THREADS);
    private final String workerId = UUID.randomUUID().toString();

    @Inject
    MongoCollection<WorkflowRun> runCollection;

    @Inject
    MongoCollection<WorkflowNodeRun> nodeRunCollection;

    @Inject
    NodeExecutor nodeExecutor;

    @Inject
    WorkflowGraphLoader graphLoader;

    @Inject
    SandboxService sandboxService;

    /** Drive a run on a background thread. The claim is idempotent — a concurrent claim elsewhere just no-ops. */
    public void submit(String runId) {
        driverPool.execute(() -> {
            try {
                advance(runId);
            } catch (RuntimeException e) {
                LOGGER.error("workflow run drive failed, runId={}", runId, e);
            }
        });
    }

    /** Claim the run and drive it to completion. Returns false if another replica already owns it. */
    public boolean advance(String runId) {
        if (!claim(runId)) {
            return false;
        }
        // A node-run still RUNNING at claim time is an orphan from a previous owner that lost its lease or crashed.
        // Settle it deterministically BEFORE driving, so recovery yields a clear FAILED instead of a race where the
        // new owner instantly classifies a still-RUNNING node as a stuck run (null-error FAILED).
        resetOrphanNodeRuns(runId);
        var lostLease = new AtomicBoolean(false);
        ScheduledFuture<?> heartbeat = null;
        try {
            heartbeat = heartbeatScheduler.scheduleAtFixedRate(
                () -> renewLease(runId, lostLease), HEARTBEAT_PERIOD_SECONDS, HEARTBEAT_PERIOD_SECONDS, TimeUnit.SECONDS);
            WorkflowRun run = runCollection.get(runId).orElseThrow(() -> new IllegalStateException("run not found: " + runId));
            var graph = graphLoader.load(run.versionId);
            var journal = new MongoWorkflowJournal(nodeRunCollection);
            RunStatus status = WorkflowAdvancer.drive(graph, run, journal, nodeExecutor, nodePool,
                () -> isCancelled(runId), () -> !lostLease.get());
            if (lostLease.get()) {
                LOGGER.warn("lease lost mid-drive, leaving finalization to the new owner, runId={}", runId);
                return true;
            }
            if (status == RunStatus.PAUSED) {
                pause(runId);   // parked on human input — not terminal, resume endpoint will wake it
                return true;
            }
            boolean completed = status == RunStatus.COMPLETED;
            WorkflowNodeRun endRun = completed ? endNodeRun(runId) : null;
            terminate(runId, status, status == RunStatus.FAILED ? failureSummary(runId) : null,
                endRun != null ? endRun.output : null,
                endRun != null && endRun.artifacts != null ? endRun.artifacts : List.of());
            return true;
        } catch (RuntimeException e) {
            if (lostLease.get()) {
                LOGGER.warn("lease lost mid-drive (after error), leaving finalization to the new owner, runId={}", runId, e);
                return true;
            }
            LOGGER.error("workflow run failed to advance, runId={}", runId, e);
            terminate(runId, RunStatus.FAILED, e.getMessage(), null, List.of());
            return true;
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            // release the per-run CODE sandbox (shared by all CODE nodes). drive() drained all node tasks before
            // returning, so none is still executing. No-op if no CODE node ran or sandboxing is disabled.
            sandboxService.releaseSandbox("wf-code:" + runId);
        }
    }

    // Run-level CAS claim, mirroring AgentScheduler.processSchedule: a PENDING row (lease_until seeded = now)
    // or a run whose lease has expired can be claimed; updated != 1 means another replica owns it.
    private boolean claim(String runId) {
        var now = ZonedDateTime.now();
        long updated = runCollection.update(
            Filters.and(
                Filters.eq("_id", runId),
                Filters.in("status", RunStatus.PENDING, RunStatus.RUNNING),
                Filters.lte("lease_until", now)),
            Updates.combine(
                Updates.set("claimed_by", workerId),
                Updates.set("status", RunStatus.RUNNING),
                Updates.set("lease_until", now.plusSeconds(LEASE_SECONDS))));
        if (updated != 1) {
            return false;
        }
        // stamp the first claim only; guarded on null because insert writes started_at as an explicit null,
        // which $min would keep (null sorts below dates in BSON) — re-claims (resume / lease takeover) no-op
        runCollection.update(
            Filters.and(Filters.eq("_id", runId), Filters.eq("started_at", null)),
            Updates.set("started_at", now));
        return true;
    }

    // Independent of node completion: a slow node must never let the lease expire. Renew only while we still own
    // the run (claimed_by == workerId); if the update matches 0 rows another replica has claimed it, so flag the
    // lease lost — the drive loop then stops dispatching and the runner skips finalization (no phantom execution,
    // no overwriting the new owner). A transient Mongo failure is NOT treated as lost (one blip is harmless; we
    // retry next tick) — scheduleAtFixedRate would otherwise cancel the task forever if it threw.
    private void renewLease(String runId, AtomicBoolean lostLease) {
        if (lostLease.get()) {
            return;
        }
        try {
            long updated = runCollection.update(
                Filters.and(Filters.eq("_id", runId), Filters.eq("claimed_by", workerId)),
                Updates.set("lease_until", ZonedDateTime.now().plusSeconds(LEASE_SECONDS)));
            if (updated != 1) {
                lostLease.set(true);
                LOGGER.warn("lease lost (renew matched {} rows), runId={}", updated, runId);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("lease renew failed, will retry next tick, runId={}", runId, e);
        }
    }

    // Park a run on human input: PAUSED, no completed_at (not terminal). Guarded by claimed_by + still-RUNNING so
    // a stolen lease can't park someone else's run. PAUSED is excluded from the claim filter, so the job won't
    // pick it up — only the resume endpoint flips it back to PENDING.
    private void pause(String runId) {
        long updated = runCollection.update(
            Filters.and(Filters.eq("_id", runId), Filters.eq("claimed_by", workerId), Filters.eq("status", RunStatus.RUNNING)),
            Updates.set("status", RunStatus.PAUSED));
        if (updated == 0) {
            LOGGER.warn("workflow run not paused (lost claim or already terminal), runId={}", runId);
        } else {
            LOGGER.info("workflow run paused on human input, runId={}", runId);
        }
    }

    // Settle node-runs left RUNNING by a previous owner (lease lost / crash). updateMany; 0 rows on a fresh claim.
    private void resetOrphanNodeRuns(String runId) {
        long reset = nodeRunCollection.update(
            Filters.and(Filters.eq("run_id", runId), Filters.eq("status", NodeRunStatus.RUNNING)),
            Updates.combine(
                Updates.set("status", NodeRunStatus.FAILED_RETRYABLE),
                Updates.set("error", "node interrupted: the previous run owner lost its lease or crashed"),
                Updates.set("completed_at", ZonedDateTime.now())));
        if (reset > 0) {
            LOGGER.warn("reset {} orphaned RUNNING node-run(s) on claim, runId={}", reset, runId);
        }
    }

    // Cross-replica cancel goes through Mongo state, never the in-process pool (which lives on a different pod).
    // A transient read failure must not fail the run — a one-tick miss is harmless, we re-read next iteration.
    private boolean isCancelled(String runId) {
        try {
            return runCollection.get(runId).map(run -> run.status == RunStatus.CANCELLED).orElse(false);
        } catch (RuntimeException e) {
            LOGGER.warn("cancel poll read failed, treating as not-cancelled, runId={}", runId, e);
            return false;
        }
    }

    // Guarded terminal transition: only the owning worker may settle a not-yet-finalized run (completed_at == null).
    // Guarding on completed_at (rather than status == RUNNING) also lets a CANCELLED run be finalized with a
    // completed_at, while still no-op'ing on a stolen lease (claimed_by changed) or a double-finalize.
    private void terminate(String runId, RunStatus status, String error, String output, List<ArtifactRef> artifacts) {
        var now = ZonedDateTime.now();
        var sets = new ArrayList<Bson>();
        sets.add(Updates.set("status", status));
        sets.add(Updates.set("completed_at", now));
        if (error != null) {
            sets.add(Updates.set("error", error));
        }
        if (output != null) {
            sets.add(Updates.set("output", output));
        }
        if (artifacts != null && !artifacts.isEmpty()) {
            sets.add(Updates.set("artifacts", artifacts));
        }
        long updated = runCollection.update(
            Filters.and(
                Filters.eq("_id", runId),
                Filters.eq("claimed_by", workerId),
                Filters.eq("completed_at", null)),
            Updates.combine(sets));
        if (updated == 0) {
            LOGGER.warn("workflow run not finalized (lost claim or already terminal), runId={}, status={}", runId, status);
        } else {
            LOGGER.info("workflow run finished, runId={}, status={}", runId, status);
        }
    }

    private String failureSummary(String runId) {
        for (WorkflowNodeRun nodeRun : nodeRunCollection.find(Filters.and(
            Filters.eq("run_id", runId),
            Filters.eq("status", NodeRunStatus.FAILED_RETRYABLE)))) {
            if (nodeRun.error != null && !nodeRun.error.isBlank()) {
                return failedNodeLabel(nodeRun) + " failed: " + nodeRun.error;
            }
        }
        return "workflow failed: no runnable path reached a terminal output";
    }

    private String failedNodeLabel(WorkflowNodeRun nodeRun) {
        var nodeId = nodeRun.nodeId != null ? nodeRun.nodeId : "unknown";
        var prefix = nodeRun.nodeType != null && !nodeRun.nodeType.isBlank()
            ? nodeRun.nodeType + " node " + nodeId
            : "node " + nodeId;
        return nodeRun.childRunId != null && !nodeRun.childRunId.isBlank()
            ? prefix + " (child run " + nodeRun.childRunId + ")"
            : prefix;
    }

    // The run's output and delivered files both come from the single COMPLETED END node-run (single-END is
    // enforced at publish). END declares the deliverables (EndExecutor.composeDeliverables); an intermediate
    // node's file is lifted to the run result only when END's output/artifacts references it — otherwise it
    // stays visible per node-run in the trace only.
    private WorkflowNodeRun endNodeRun(String runId) {
        for (WorkflowNodeRun nodeRun : nodeRunCollection.find(Filters.and(
            Filters.eq("run_id", runId),
            Filters.eq("node_type", "END"),
            Filters.eq("status", NodeRunStatus.COMPLETED)))) {
            return nodeRun;
        }
        return null;
    }
}
