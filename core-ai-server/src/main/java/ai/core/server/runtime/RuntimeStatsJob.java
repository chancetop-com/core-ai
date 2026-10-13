package ai.core.server.runtime;

import ai.core.sse.SseQueueLimits;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;

/**
 * Publishes the runtime gauges (heap watermark, in-flight work, refusals, queue depths) into the action
 * log once a minute, which is how this service is observed everywhere else: the numbers land in the
 * action index, so a Kibana panel can chart them without introducing a metrics stack.
 *
 * @author stephen
 */
public class RuntimeStatsJob implements Job {
    @Inject
    RuntimeStatsService statsService;
    @Inject
    RuntimeCapacityGuard guard;

    @Override
    public void execute(JobContext context) {
        var stats = statsService.stats();
        var limits = guard.limits();
        // re-applied here so an admin change reaches the SSE write path without a restart
        SseQueueLimits.configure(limits.sseMaxQueueDepth, limits.sseMaxQueueBytes);

        ActionLogContext.stat("runtime.heap_used_percent", stats.memory.heapUsedPercent);
        ActionLogContext.stat("runtime.heap_watermark_percent", limits.heapWatermarkPercent);
        ActionLogContext.stat("runtime.sessions_active", stats.sessions.active);
        ActionLogContext.stat("runtime.in_flight_turns", stats.turns.inFlight);
        ActionLogContext.stat("runtime.turns_saturated_total", stats.turns.saturatedTotal);
        ActionLogContext.stat("runtime.turns_rejected_total", stats.turns.rejectedTotal);
        ActionLogContext.stat("runtime.runs_active", stats.runs.active);
        ActionLogContext.stat("runtime.runs_queued", stats.runs.queued);
        ActionLogContext.stat("runtime.runs_rejected_total", stats.runs.rejectedTotal);
        ActionLogContext.stat("runtime.sandboxes_live", stats.sandbox.live);
        ActionLogContext.stat("runtime.sandboxes_rejected_total", stats.sandbox.rejectedTotal);
        ActionLogContext.stat("runtime.sandbox_claims_live", stats.sandboxFleet.claimsLive);
        ActionLogContext.stat("runtime.sandbox_direct_crs_live", stats.sandboxFleet.directCrsLive);
        ActionLogContext.stat("runtime.sandbox_creates_failed_total", stats.sandboxFleet.createsFailedTotal);
        ActionLogContext.stat("runtime.span_ingest_depth", stats.spanIngest.queueDepth);
        ActionLogContext.stat("runtime.span_ingest_dropped_total", stats.spanIngest.droppedTotal);
        // context (not stats) so a Kibana query can group by pod when several replicas report
        ActionLogContext.put("runtime_pod", stats.pod);
        ActionLogContext.put("runtime_admission_enabled", limits.admissionEnabled);
    }
}
