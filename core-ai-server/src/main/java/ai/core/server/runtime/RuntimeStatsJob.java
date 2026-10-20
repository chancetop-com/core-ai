package ai.core.server.runtime;

import ai.core.api.server.runtime.RuntimeStatsView;
import ai.core.sse.SseQueueLimits;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.mongo.MongoCollection;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Publishes the runtime gauges (heap watermark, in-flight work, refusals, queue depths) into the action
 * log once a minute, which is how this service is observed everywhere else: the numbers land in the
 * action index, so a Kibana panel can chart them without introducing a metrics stack.
 *
 * @author stephen
 */
public class RuntimeStatsJob implements Job {
    private static void stat(String key, Number value) {
        if (value != null) ActionLogContext.stat(key, value.doubleValue());
    }

    @Inject
    RuntimeStatsService statsService;
    @Inject
    RuntimeCapacityGuard guard;
    @Inject
    MongoCollection<RuntimeSample> runtimeSampleCollection;

    @Override
    public void execute(JobContext context) {
        var stats = statsService.stats();
        var limits = guard.limits();
        // re-applied here so an admin change reaches the SSE write path without a restart
        SseQueueLimits.configure(limits.sseMaxQueueDepth, limits.sseMaxQueueBytes);

        stat("runtime.heap_used_percent", stats.memory.heapUsedPercent);
        stat("runtime.heap_before_gc_percent", stats.memory.heapBeforeGcPercent);
        stat("runtime.heap_after_gc_percent", stats.memory.heapAfterGcPercent);
        stat("runtime.container_memory_percent", stats.memory.containerMemoryPercent);
        stat("runtime.heap_watermark_percent", limits.heapWatermarkPercent);
        stat("runtime.sessions_active", stats.sessions.active);
        stat("runtime.sessions_estimated_bytes_total", stats.sessions.estimatedBytesTotal);
        stat("runtime.sessions_estimated_bytes_p50", stats.sessions.estimatedBytesP50);
        stat("runtime.sessions_context_tokens_p50", stats.sessions.contextTokensP50);
        stat("runtime.sessions_inline_image_bytes_total", stats.sessions.inlineImageBytesTotal);
        stat("runtime.sessions_idle_seconds_p50", stats.sessions.idleSecondsP50);
        stat("runtime.sessions_created_total", stats.sessions.createdTotal);
        stat("runtime.sessions_closed_total", stats.sessions.closedTotal);
        stat("runtime.sessions_idle_evicted_total", stats.sessions.idleEvictedTotal);
        stat("runtime.sessions_turns_21plus", stats.sessions.turnBucketCounts.getOrDefault("21+", 0));
        stat("runtime.in_flight_turns", stats.turns.inFlight);
        stat("runtime.turns_saturated_total", stats.turns.saturatedTotal);
        stat("runtime.turns_rejected_total", stats.turns.rejectedTotal);
        stat("runtime.runs_active", stats.runs.active);
        stat("runtime.runs_queued", stats.runs.queued);
        stat("runtime.runs_rejected_total", stats.runs.rejectedTotal);
        stat("runtime.sandboxes_live", stats.sandbox.live);
        stat("runtime.sandboxes_rejected_total", stats.sandbox.rejectedTotal);
        stat("runtime.sandbox_claims_live", stats.sandboxFleet.claimsLive);
        stat("runtime.sandbox_direct_crs_live", stats.sandboxFleet.directCrsLive);
        stat("runtime.sandbox_creates_failed_total", stats.sandboxFleet.createsFailedTotal);
        stat("runtime.span_ingest_depth", stats.spanIngest.queueDepth);
        stat("runtime.span_ingest_dropped_total", stats.spanIngest.droppedTotal);
        // context (not stats) so a Kibana query can group by pod when several replicas report
        ActionLogContext.put("runtime_pod", stats.pod);
        ActionLogContext.put("runtime_admission_enabled", limits.admissionEnabled);
        recordSample(stats);
    }

    /** The long-term series: one document per pod per tick, read back by the runtime dashboard. */
    private void recordSample(RuntimeStatsView stats) {
        var sample = new RuntimeSample();
        sample.id = UUID.randomUUID().toString();
        sample.pod = stats.pod;
        sample.collectedAt = ZonedDateTime.now();
        applyMemory(sample, stats.memory);
        applySessions(sample, stats.sessions);
        sample.inFlightTurns = stats.turns.inFlight;
        sample.turnsSaturated = stats.turns.saturatedTotal;
        sample.turnsRejected = stats.turns.rejectedTotal;
        sample.runsActive = stats.runs.active;
        sample.runsQueued = stats.runs.queued;
        sample.runsRejected = stats.runs.rejectedTotal;
        sample.sandboxesLive = stats.sandbox.live;
        sample.sandboxesRejected = stats.sandbox.rejectedTotal;
        sample.spanIngestDepth = stats.spanIngest.queueDepth;
        sample.spanIngestDropped = stats.spanIngest.droppedTotal;
        sample.claimsLive = stats.sandboxFleet.claimsLive;
        sample.directCrsLive = stats.sandboxFleet.directCrsLive;
        sample.createsTotal = stats.sandboxFleet.createsTotal;
        sample.createsFailedTotal = stats.sandboxFleet.createsFailedTotal;
        runtimeSampleCollection.insert(sample);
    }

    private void applyMemory(RuntimeSample sample, RuntimeStatsView.MemoryStats memory) {
        sample.heapUsedPercent = memory.heapUsedPercent;
        sample.heapUsed = memory.heapUsed;
        sample.heapMax = memory.heapMax;
        sample.heapBeforeGc = memory.heapBeforeGc;
        sample.heapBeforeGcPercent = memory.heapBeforeGcPercent;
        sample.heapAfterGc = memory.heapAfterGc;
        sample.heapAfterGcPercent = memory.heapAfterGcPercent;
        sample.gcId = memory.gcId;
        sample.gcAgeMs = memory.gcAgeMs;
        sample.containerMemory = memory.containerMemory;
        sample.containerMemoryMax = memory.containerMemoryMax;
        sample.containerMemoryPercent = memory.containerMemoryPercent;
        sample.rss = memory.rss;
    }

    private void applySessions(RuntimeSample sample, RuntimeStatsView.SessionStats sessions) {
        sample.sessionsActive = sessions.active;
        sample.sessionEstimateVersion = sessions.estimateVersion;
        sample.sessionsEstimatedBytesTotal = sessions.estimatedBytesTotal;
        sample.sessionsEstimatedBytesP50 = sessions.estimatedBytesP50;
        sample.sessionsEstimatedBytesP90 = sessions.estimatedBytesP90;
        sample.sessionsEstimatedBytesP99 = sessions.estimatedBytesP99;
        sample.sessionsEstimatedBytesMax = sessions.estimatedBytesMax;
        sample.sessionsContextTokensTotal = sessions.contextTokensTotal;
        sample.sessionsContextTokensP50 = sessions.contextTokensP50;
        sample.sessionsContextTokensP90 = sessions.contextTokensP90;
        sample.sessionsInlineImageBytesTotal = sessions.inlineImageBytesTotal;
        sample.sessionsIdleSecondsP50 = sessions.idleSecondsP50;
        sample.sessionsIdleSecondsP90 = sessions.idleSecondsP90;
        sample.sessionTurnBucketCounts = sessions.turnBucketCounts;
        sample.sessionTurnBucketEstimatedBytes = sessions.turnBucketEstimatedBytes;
        sample.sessionTurnBucketEstimatedBytesP50 = sessions.turnBucketEstimatedBytesP50;
        sample.sessionTurnBucketEstimatedBytesP90 = sessions.turnBucketEstimatedBytesP90;
        sample.sessionTurnBucketContextTokens = sessions.turnBucketContextTokens;
        sample.sessionsCreatedTotal = sessions.createdTotal;
        sample.sessionsClosedTotal = sessions.closedTotal;
        sample.sessionsIdleEvictedTotal = sessions.idleEvictedTotal;
    }
}
