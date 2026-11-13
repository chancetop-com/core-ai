package ai.core.server.trace.maintenance;

import ai.core.server.run.AgentRunQuery;

import ai.core.server.run.AgentRunStore;

import ai.core.server.domain.AgentRun;
import ai.core.server.domain.RunStatus;
import ai.core.server.trace.TraceQuery;
import ai.core.server.trace.TraceStore;
import ai.core.server.trace.TraceUpdate;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceStatus;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Finalizes runs and traces that a pod loss left behind: both are written by the process that executes them,
 * so a crash (or a restart mid-run) keeps them RUNNING forever — the run never gets its status, and its trace
 * never gets the root span that carries the final status and the identity the trace list shows.
 * <p>
 * A run whose trace did finish is reconciled from that trace instead of being failed: the root span lands
 * before the run status is written, so a pod lost in between has the outcome on record already.
 *
 * @author stephen
 */
public class StaleRunCleanupService {
    private static final Logger LOGGER = LoggerFactory.getLogger(StaleRunCleanupService.class);
    static final Duration STALE_AFTER = Duration.ofMinutes(30);
    private static final int MAX_BATCH = 200;
    private static final String INTERRUPTED = "run interrupted: no activity for " + STALE_AFTER.toMinutes() + "m";

    @Inject
    TraceStore traceStore;
    @Inject
    AgentRunStore agentRunStore;

    public void cleanup() {
        var cutoff = ZonedDateTime.now().minus(STALE_AFTER);
        int traces = finishStaleTraces(cutoff);
        int runs = finishStaleRuns(cutoff);
        if (traces > 0 || runs > 0) {
            LOGGER.info("finished stale traces and runs, traces={}, runs={}", traces, runs);
        }
    }

    private int finishStaleTraces(ZonedDateTime cutoff) {
        int finished = 0;
        for (var trace : runningTracesStartedBefore(cutoff)) {
            if (isStale(trace.updatedAt, cutoff)) {
                finishTrace(trace);
                finished++;
            }
        }
        return finished;
    }

    private int finishStaleRuns(ZonedDateTime cutoff) {
        int finished = 0;
        for (var run : unfinishedRunsStartedBefore(cutoff)) {
            var trace = run.traceId != null && !run.traceId.isBlank() ? traceOf(run.traceId) : null;
            if (trace != null && isTerminal(trace.status)) {
                reconcile(run, trace);
                finished++;
            } else if (trace == null || isStale(trace.updatedAt, cutoff)) {
                fail(run, trace);
                finished++;
            }
        }
        return finished;
    }

    // Only a span that lands refreshes updated_at, so a run whose process is gone leaves the trace untouched
    // while a slow but healthy run keeps it fresh — that difference is what makes "stale" safe to act on.
    List<Trace> runningTracesStartedBefore(ZonedDateTime cutoff) {
        var query = new TraceQuery();
        query.status = TraceStatus.RUNNING.name();
        query.startTo = cutoff;
        query.ascending = true;
        query.limit = MAX_BATCH;
        return traceStore.list(query);
    }

    List<AgentRun> unfinishedRunsStartedBefore(ZonedDateTime cutoff) {
        var query = new AgentRunQuery();
        query.statuses = List.of(RunStatus.RUNNING, RunStatus.PENDING);
        query.to = cutoff;
        query.ascending = true;
        query.limit = MAX_BATCH;
        return agentRunStore.find(query);
    }

    private Trace traceOf(String traceId) {
        return traceStore.findByTraceId(traceId);
    }

    private void finishTrace(Trace trace) {
        var lastActivity = trace.updatedAt != null ? trace.updatedAt : ZonedDateTime.now();
        var update = new TraceUpdate();
        update.traceId = trace.traceId;
        update.requireStatus = TraceStatus.RUNNING;
        update.status = TraceStatus.ERROR;
        update.errorMessage = INTERRUPTED;
        update.completedAt = lastActivity;
        update.updatedAt = ZonedDateTime.now();
        if (trace.startedAt != null) {
            update.durationMs = Math.max(0L, Duration.between(trace.startedAt, lastActivity).toMillis());
        }
        traceStore.update(update);
        LOGGER.info("finished interrupted trace, traceId={}, started={}", trace.traceId, trace.startedAt);
    }

    private void reconcile(AgentRun run, Trace trace) {
        var completed = trace.status == TraceStatus.COMPLETED;
        agentRunStore.reconcileIfUnfinished(run.id, completed, trace.output, trace.errorMessage,
            trace.completedAt != null ? trace.completedAt : ZonedDateTime.now());
        LOGGER.info("reconciled run from its trace, runId={}, traceId={}, traceStatus={}", run.id, trace.traceId, trace.status);
    }

    private void fail(AgentRun run, Trace trace) {
        agentRunStore.failIfUnfinished(run.id, INTERRUPTED, ZonedDateTime.now());
        LOGGER.info("failed interrupted run, runId={}, traceId={}, lastTraceActivity={}",
            run.id, run.traceId, trace != null ? trace.updatedAt : null);
    }

    private boolean isTerminal(TraceStatus status) {
        return status == TraceStatus.COMPLETED || status == TraceStatus.ERROR || status == TraceStatus.CANCELLED;
    }

    private boolean isStale(ZonedDateTime updatedAt, ZonedDateTime cutoff) {
        return updatedAt == null || updatedAt.isBefore(cutoff);
    }
}



