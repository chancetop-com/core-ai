package ai.core.server.workflow;

import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowRun;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;

/**
 * Scans for claimable workflow runs and hands each to {@link WorkflowRunner} off-thread. One filter covers both
 * paths: a freshly created PENDING run (lease_until seeded = now) and a crashed RUNNING run whose lease expired.
 * Actively-driven runs keep their lease renewed by the heartbeat, so they fall outside the filter. The CAS in
 * advance() dedupes across replicas. Mirrors AgentSchedulerJob.
 *
 * <p>Also recovers PAUSED runs stranded by the concurrent-approve settle/park race (see
 * {@link #recoverStrandedPausedRuns}).
 *
 * @author Xander
 */
public class WorkflowRunnerJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowRunnerJob.class);

    @Inject
    MongoCollection<WorkflowRun> runCollection;

    @Inject
    MongoCollection<WorkflowNodeRun> nodeRunCollection;

    @Inject
    WorkflowRunner workflowRunner;

    @Override
    public void execute(JobContext context) {
        var now = ZonedDateTime.now();
        var claimable = runCollection.find(Filters.and(
            Filters.in("status", RunStatus.PENDING, RunStatus.RUNNING),
            Filters.lte("lease_until", now)));
        for (WorkflowRun run : claimable) {
            workflowRunner.submit(run.id);
        }
        recoverStrandedPausedRuns(now);
    }

    // A correctly PAUSED run always has a WAITING node-run (that is why it parked). One with none is the residue of
    // a human resume that settled the last WAITING node just as the driver parked the run on a stale snapshot (the
    // rare settle/park race the concurrent-approve path opens): no driver is left to fold the now-ready downstream.
    // Flip it back to PENDING so the claim path re-drives it. Gated on an expired lease (status,lease_until index)
    // so we only ever touch a run whose driver is long gone, never one mid-pause.
    private void recoverStrandedPausedRuns(ZonedDateTime now) {
        var stalePaused = runCollection.find(Filters.and(
            Filters.eq("status", RunStatus.PAUSED),
            Filters.lte("lease_until", now)));
        for (WorkflowRun run : stalePaused) {
            if (recoverTerminalChildWaits(run)) {
                continue;
            }
            if (hasWaitingNode(run.id)) {
                continue;   // a genuine human gate is still open — leave it parked
            }
            long woken = runCollection.update(
                Filters.and(Filters.eq("_id", run.id), Filters.eq("status", RunStatus.PAUSED)),
                Updates.combine(Updates.set("status", RunStatus.PENDING), Updates.set("lease_until", now)));
            if (woken == 1) {
                LOGGER.warn("recovered stranded PAUSED run with no waiting node (resume settle/park race), runId={}", run.id);
                workflowRunner.submit(run.id);
            }
        }
    }

    // If a child workflow finished but the process died before wakeParent completed, the parent is PAUSED with a
    // WAITING WORKFLOW node forever. Replaying the wake is idempotent and settles that parked node.
    private boolean recoverTerminalChildWaits(WorkflowRun parent) {
        boolean recovered = false;
        var waits = nodeRunCollection.find(Filters.and(
            Filters.eq("run_id", parent.id),
            Filters.eq("status", NodeRunStatus.WAITING)));
        for (WorkflowNodeRun wait : waits) {
            if (wait.childRunId == null || wait.childRunId.isBlank()) {
                continue;
            }
            WorkflowRun child = runCollection.get(wait.childRunId).orElse(null);
            if (child == null || !isTerminal(child.status) || child.completedAt == null) {
                continue;
            }
            if (workflowRunner.wakeParent(child)) {
                recovered = true;
                workflowRunner.submit(parent.id);
            }
        }
        return recovered;
    }

    private boolean hasWaitingNode(String runId) {
        return !nodeRunCollection.find(Filters.and(
            Filters.eq("run_id", runId),
            Filters.eq("status", NodeRunStatus.WAITING))).isEmpty();
    }

    private static boolean isTerminal(RunStatus status) {
        return status == RunStatus.COMPLETED || status == RunStatus.FAILED || status == RunStatus.TIMEOUT || status == RunStatus.CANCELLED;
    }
}
