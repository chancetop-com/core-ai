package ai.core.server.workflow;

import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowRun;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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

    private final ExecutorService nodePool = Executors.newFixedThreadPool(MAX_CONCURRENT_NODES);
    private final ExecutorService driverPool = Executors.newFixedThreadPool(MAX_CONCURRENT_RUNS);
    private final ScheduledExecutorService heartbeatScheduler = Executors.newScheduledThreadPool(1);
    private final String workerId = UUID.randomUUID().toString();

    @Inject
    MongoCollection<WorkflowRun> runCollection;

    @Inject
    MongoCollection<WorkflowNodeRun> nodeRunCollection;

    @Inject
    NodeExecutor nodeExecutor;

    @Inject
    WorkflowGraphLoader graphLoader;

    /**
     * Graceful-shutdown hand-off: make this worker's in-flight runs immediately claimable (lease_until=now) so a
     * live replica resumes them at once instead of waiting out the lease. Safe because ownership is re-established
     * by the CAS in {@link #claim} and per-node dispatch is deduped by the journal's unique index. A hard crash
     * skips this; the runner job then recovers those once their lease naturally expires.
     */
    public void releaseClaimedRuns() {
        long released = runCollection.update(
            Filters.and(Filters.eq("claimed_by", workerId), Filters.eq("status", RunStatus.RUNNING)),
            Updates.set("lease_until", ZonedDateTime.now()));
        if (released > 0) {
            LOGGER.info("released {} in-flight workflow runs for hand-off on shutdown, worker={}", released, workerId);
        }
    }

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
        ScheduledFuture<?> heartbeat = null;
        try {
            heartbeat = heartbeatScheduler.scheduleAtFixedRate(
                () -> renewLease(runId), HEARTBEAT_PERIOD_SECONDS, HEARTBEAT_PERIOD_SECONDS, TimeUnit.SECONDS);
            WorkflowRun run = runCollection.get(runId).orElseThrow(() -> new IllegalStateException("run not found: " + runId));
            var graph = graphLoader.load(run.versionId);
            var journal = new MongoWorkflowJournal(nodeRunCollection);
            RunStatus status = WorkflowAdvancer.drive(graph, run, journal, nodeExecutor, nodePool, () -> isCancelled(runId));
            terminate(runId, status, null, status == RunStatus.COMPLETED ? endOutput(runId) : null);
            return true;
        } catch (RuntimeException e) {
            LOGGER.error("workflow run failed to advance, runId={}", runId, e);
            terminate(runId, RunStatus.FAILED, e.getMessage(), null);
            return true;
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
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
        return updated == 1;
    }

    // Independent of node completion: a slow node must never let the lease expire. Tolerant of a transient
    // failure — scheduleAtFixedRate cancels the task forever if it throws, so one blip must not silence it.
    private void renewLease(String runId) {
        try {
            runCollection.update(
                Filters.and(Filters.eq("_id", runId), Filters.eq("claimed_by", workerId)),
                Updates.set("lease_until", ZonedDateTime.now().plusSeconds(LEASE_SECONDS)));
        } catch (RuntimeException e) {
            LOGGER.warn("lease renew failed, will retry next tick, runId={}", runId, e);
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

    // Status-guarded terminal transition: only the owning worker on a still-RUNNING run may settle it, so a
    // concurrent CANCELLED (or a stolen lease) is never blindly overwritten. On success the run's single END
    // output is bubbled up to run.output (what run-sync and the history view return).
    private void terminate(String runId, RunStatus status, String error, String output) {
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
        long updated = runCollection.update(
            Filters.and(
                Filters.eq("_id", runId),
                Filters.eq("claimed_by", workerId),
                Filters.eq("status", RunStatus.RUNNING)),
            Updates.combine(sets));
        if (updated == 0) {
            LOGGER.warn("workflow run not finalized (lost claim or already terminal), runId={}, status={}", runId, status);
        } else {
            LOGGER.info("workflow run finished, runId={}, status={}", runId, status);
        }
    }

    // The run's output = the single COMPLETED END node-run's output (single-END is enforced at publish).
    private String endOutput(String runId) {
        for (WorkflowNodeRun nodeRun : nodeRunCollection.find(Filters.and(
            Filters.eq("run_id", runId),
            Filters.eq("node_type", "END"),
            Filters.eq("status", NodeRunStatus.COMPLETED)))) {
            return nodeRun.output;
        }
        return null;
    }
}
