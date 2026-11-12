package ai.core.server.workflow;

import ai.core.server.domain.ArtifactRef;
import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.NotificationCategory;
import ai.core.server.domain.NotificationType;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.notification.NotificationService;
import ai.core.server.sandbox.SandboxService;


import core.framework.inject.Inject;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
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

    private static boolean isTerminal(RunStatus status) {
        return status == RunStatus.COMPLETED || status == RunStatus.FAILED || status == RunStatus.TIMEOUT || status == RunStatus.CANCELLED;
    }

    private final ExecutorService nodePool = Executors.newFixedThreadPool(MAX_CONCURRENT_NODES);
    private final ExecutorService driverPool = Executors.newFixedThreadPool(MAX_CONCURRENT_RUNS);
    private final ScheduledExecutorService heartbeatScheduler = Executors.newScheduledThreadPool(HEARTBEAT_THREADS);
    private final String workerId = UUID.randomUUID().toString();

    @Inject
    WorkflowRunStore runStore;

    @Inject
    WorkflowNodeRunStore nodeRunStore;

    @Inject
    NodeExecutor nodeExecutor;

    @Inject
    WorkflowGraphLoader graphLoader;

    @Inject
    WorkflowRunGateway workflowRunGateway;

    @Inject
    SandboxService sandboxService;

    @Inject
    NotificationService notificationService;

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
        WorkflowRun run = null;
        // A node-run still RUNNING at claim time is an orphan from a previous owner that lost its lease or crashed.
        // Settle it deterministically BEFORE driving, so recovery yields a clear FAILED instead of a race where the
        // new owner instantly classifies a still-RUNNING node as a stuck run (null-error FAILED).
        resetOrphanNodeRuns(runId);
        var lostLease = new AtomicBoolean(false);
        ScheduledFuture<?> heartbeat = null;
        try {
            heartbeat = heartbeatScheduler.scheduleAtFixedRate(
                () -> renewLease(runId, lostLease), HEARTBEAT_PERIOD_SECONDS, HEARTBEAT_PERIOD_SECONDS, TimeUnit.SECONDS);
            run = runStore.get(runId).orElseThrow(() -> new IllegalStateException("run not found: " + runId));
            var graph = graphLoader.load(run.versionId);
            var journal = new StoreWorkflowJournal(nodeRunStore);
            RunStatus status = WorkflowAdvancer.drive(graph, run, journal,
                new WorkflowAdvancer.ExecCtx(nodeExecutor, nodePool),
                () -> isCancelled(runId), () -> !lostLease.get());
            return handleDriveResult(runId, run, status, lostLease);
        } catch (RuntimeException e) {
            if (lostLease.get()) {
                LOGGER.warn("lease lost mid-drive (after error), leaving finalization to the new owner, runId={}", runId, e);
                return true;
            }
            LOGGER.error("workflow run failed to advance, runId={}", runId, e);
            boolean finalized = terminate(runId, RunStatus.FAILED, e.getMessage(), null, List.of());
            if (finalized && run != null) {
                notificationService.create(run.userId,
                    NotificationCategory.SYSTEM, NotificationType.TERMINATE,
                    "Workflow Failed", "Workflow \"" + run.workflowId + "\" encountered an error",
                    new NotificationService.CreateContext(null, run.sessionId));
            }
            if (finalized && run != null && run.parentRunId != null) {
                runStore.get(runId).ifPresent(this::wakeParent);
            }
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
        if (!runStore.claim(runId, workerId, now, now.plusSeconds(LEASE_SECONDS))) {
            return false;
        }
        // stamp the first claim only; a re-claim (resume / lease takeover) must not move it
        runStore.markStartedIfNull(runId, now);
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
            if (!runStore.renewLease(runId, workerId, ZonedDateTime.now().plusSeconds(LEASE_SECONDS))) {
                lostLease.set(true);
                LOGGER.warn("lease lost, runId={}", runId);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("lease renew failed, will retry next tick, runId={}", runId, e);
        }
    }

    // Park a run on human input: PAUSED, no completed_at (not terminal). Guarded by claimed_by + still-RUNNING so
    // a stolen lease can't park someone else's run. PAUSED is excluded from the claim filter, so the job won't
    // pick it up — only the resume endpoint flips it back to PENDING.
    private void pause(String runId) {
        if (!runStore.pause(runId, workerId)) {
            LOGGER.warn("workflow run not paused (lost claim or already terminal), runId={}", runId);
        } else {
            LOGGER.info("workflow run paused on human input, runId={}", runId);
        }
    }

    // Settle node-runs left RUNNING by a previous owner (lease lost / crash). updateMany; 0 rows on a fresh claim.
    private void resetOrphanNodeRuns(String runId) {
        int reset = nodeRunStore.resetRunningByRun(runId, ZonedDateTime.now());
        if (reset > 0) {
            LOGGER.warn("reset {} orphaned RUNNING node-run(s) on claim, runId={}", reset, runId);
        }
    }

    // Cross-replica cancel goes through Mongo state, never the in-process pool (which lives on a different pod).
    // A transient read failure must not fail the run — a one-tick miss is harmless, we re-read next iteration.
    private boolean isCancelled(String runId) {
        try {
            return runStore.get(runId).map(run -> run.status == RunStatus.CANCELLED).orElse(Boolean.FALSE);
        } catch (RuntimeException e) {
            LOGGER.warn("cancel poll read failed, treating as not-cancelled, runId={}", runId, e);
            return false;
        }
    }

    // Guarded terminal transition: only the owning worker may settle a not-yet-finalized run (completed_at == null).
    // Guarding on completed_at (rather than status == RUNNING) also lets a CANCELLED run be finalized with a
    // completed_at, while still no-op'ing on a stolen lease (claimed_by changed) or a double-finalize.
    private boolean terminate(String runId, RunStatus status, String error, String output, List<ArtifactRef> artifacts) {
        boolean updated = runStore.terminate(runId, workerId,
            new WorkflowRunStore.RunTermination(status, error, output, artifacts, ZonedDateTime.now()));
        if (!updated) {
            LOGGER.warn("workflow run not finalized (lost claim or already terminal), runId={}, status={}", runId, status);
            return false;
        }
        LOGGER.info("workflow run finished, runId={}, status={}", runId, status);
        return true;
    }

    // A child sub-workflow run reached a terminal status: settle the parent's parked WORKFLOW node-run with the
    // child's result, then flip the parent PAUSED -> PENDING so the runner job re-claims and continues past the
    // node. Mirrors WorkflowRunService.resume() (the human-input waker), with the child terminal as the trigger.
    boolean wakeParent(WorkflowRun child) {
        if (child.parentRunId == null || child.parentNodeId == null || !isTerminal(child.status)) {
            return false;
        }
        var now = ZonedDateTime.now();
        NodeRunStatus status;
        String output = null;
        List<ArtifactRef> artifacts = null;
        String error = null;
        if (child.status == RunStatus.COMPLETED) {
            WorkflowNodeRun endRun = endNodeRun(child.id);
            status = NodeRunStatus.COMPLETED;
            output = endRun != null ? endRun.output : null;
            artifacts = endRun != null ? endRun.artifacts : null;
        } else {
            // child FAILED / CANCELLED / TIMEOUT -> the parent WORKFLOW node fails (run fails), per design no auto re-run
            status = NodeRunStatus.FAILED_RETRYABLE;
            error = "sub-workflow " + child.status + " (child run " + child.id + ")";
        }
        boolean settled = nodeRunStore.settleWaitingChild(child.parentRunId, child.parentNodeId,
            new WorkflowNodeRunStore.ChildSettlement(child.id, status, output, artifacts, error, now));
        if (!settled) {
            LOGGER.warn("parent WORKFLOW node not settled (already settled or gone), parentRunId={}, nodeId={}",
                child.parentRunId, child.parentNodeId);
            return false;
        }
        // wake: PAUSED -> PENDING, claimable now. Guarded on PAUSED so a concurrent waker only wakes once.
        runStore.wakePaused(child.parentRunId, now);
        LOGGER.info("woke parent run on sub-workflow terminal, parentRunId={}, childRunId={}, status={}",
            child.parentRunId, child.id, child.status);
        return true;
    }

    // Cascade cancel: a cancelled parent run must take its decoupled sub-workflow child runs down with it, else
    // they finish as orphaned RUNNING. Each direct child is found by parent_run_id; the gateway recurses into its
    // own descendants. cancelSubtree no-ops on already-terminal children.
    private void cancelChildren(String runId) {
        for (WorkflowRun child : runStore.findChildren(runId)) {
            workflowRunGateway.cancelSubtree(child.id);
        }
    }

    private String failureSummary(String runId) {
        for (WorkflowNodeRun nodeRun : nodeRunStore.findFailedRetryableByRun(runId)) {
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
        var nodeRuns = nodeRunStore.findByRunTypeAndStatus(runId, "END", NodeRunStatus.COMPLETED);
        return nodeRuns.isEmpty() ? null : nodeRuns.get(0);
    }

    private boolean handleDriveResult(String runId, WorkflowRun run, RunStatus status, AtomicBoolean lostLease) {
        if (lostLease.get()) {
            LOGGER.warn("lease lost mid-drive, leaving finalization to the new owner, runId={}", runId);
            return true;
        }
        if (status == RunStatus.PAUSED) {
            pause(runId);
            notificationService.create(run.userId,
                NotificationCategory.SYSTEM, NotificationType.PAUSE,
                "Workflow Paused", "Workflow \"" + run.workflowId + "\" is paused and waiting for human input",
                new NotificationService.CreateContext(null, run.sessionId));
            return true;
        }
        boolean completed = status == RunStatus.COMPLETED;
        WorkflowNodeRun endRun = completed ? endNodeRun(runId) : null;
        boolean finalized = terminate(runId, status, status == RunStatus.FAILED ? failureSummary(runId) : null,
            endRun != null ? endRun.output : null,
            endRun != null && endRun.artifacts != null ? endRun.artifacts : List.of());
        if (finalized && (status == RunStatus.FAILED || status == RunStatus.CANCELLED)) {
            var title = status == RunStatus.FAILED ? "Workflow Failed" : "Workflow Cancelled";
            var msg = "Workflow \"" + run.workflowId + "\" has " + status.name().toLowerCase(Locale.ROOT);
            notificationService.create(run.userId,
                NotificationCategory.SYSTEM, NotificationType.TERMINATE, title, msg,
                new NotificationService.CreateContext(null, run.sessionId));
        }
        if (finalized && run.parentRunId != null) {
            runStore.get(runId).ifPresent(this::wakeParent);
        }
        if (finalized && status == RunStatus.CANCELLED) {
            cancelChildren(runId);
        }
        return true;
    }
}


