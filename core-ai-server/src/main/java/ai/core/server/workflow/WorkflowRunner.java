package ai.core.server.workflow;

import ai.core.server.domain.RunStatus;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.domain.WorkflowRun;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
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
    private static final int LEASE_SECONDS = 60;
    private static final int HEARTBEAT_PERIOD_SECONDS = LEASE_SECONDS / 3;

    private final ExecutorService nodePool = Executors.newFixedThreadPool(MAX_CONCURRENT_NODES);
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
            terminate(runId, status, null);
            return true;
        } catch (RuntimeException e) {
            LOGGER.error("workflow run failed to advance, runId={}", runId, e);
            terminate(runId, RunStatus.FAILED, e.getMessage());
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
    // concurrent CANCELLED (or a stolen lease) is never blindly overwritten.
    private void terminate(String runId, RunStatus status, String error) {
        var now = ZonedDateTime.now();
        var update = error == null
            ? Updates.combine(Updates.set("status", status), Updates.set("completed_at", now))
            : Updates.combine(Updates.set("status", status), Updates.set("error", error), Updates.set("completed_at", now));
        long updated = runCollection.update(
            Filters.and(
                Filters.eq("_id", runId),
                Filters.eq("claimed_by", workerId),
                Filters.eq("status", RunStatus.RUNNING)),
            update);
        if (updated == 0) {
            LOGGER.warn("workflow run not finalized (lost claim or already terminal), runId={}, status={}", runId, status);
        } else {
            LOGGER.info("workflow run finished, runId={}, status={}", runId, status);
        }
    }
}
