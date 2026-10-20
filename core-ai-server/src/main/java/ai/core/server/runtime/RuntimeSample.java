package ai.core.server.runtime;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * One pod's runtime watermark sample, written every minute by {@link RuntimeStatsJob}. The collector
 * only writes and the dashboard only reads, so the watermarks can be tracked over time and per replica
 * instead of being visible only in the instant someone opens a page.
 *
 * @author stephen
 */
@Collection(name = "runtime_samples")
public class RuntimeSample {
    @Id
    public String id;

    @Field(name = "pod")
    public String pod;

    @Field(name = "collected_at")
    public ZonedDateTime collectedAt;

    @Field(name = "heap_used_percent")
    public Double heapUsedPercent;

    @Field(name = "heap_used")
    public Long heapUsed;

    @Field(name = "heap_max")
    public Long heapMax;

    @Field(name = "heap_before_gc")
    public Long heapBeforeGc;

    @Field(name = "heap_before_gc_percent")
    public Double heapBeforeGcPercent;

    @Field(name = "heap_after_gc")
    public Long heapAfterGc;

    @Field(name = "heap_after_gc_percent")
    public Double heapAfterGcPercent;

    @Field(name = "gc_id")
    public Long gcId;

    @Field(name = "gc_age_ms")
    public Long gcAgeMs;

    @Field(name = "container_memory")
    public Long containerMemory;

    @Field(name = "container_memory_max")
    public Long containerMemoryMax;

    @Field(name = "container_memory_percent")
    public Double containerMemoryPercent;

    @Field(name = "rss")
    public Long rss;

    @Field(name = "sessions_active")
    public Integer sessionsActive;

    @Field(name = "session_estimate_version")
    public Integer sessionEstimateVersion;

    @Field(name = "sessions_estimated_bytes_total")
    public Long sessionsEstimatedBytesTotal;

    @Field(name = "sessions_estimated_bytes_p50")
    public Long sessionsEstimatedBytesP50;

    @Field(name = "sessions_estimated_bytes_p90")
    public Long sessionsEstimatedBytesP90;

    @Field(name = "sessions_estimated_bytes_p99")
    public Long sessionsEstimatedBytesP99;

    @Field(name = "sessions_estimated_bytes_max")
    public Long sessionsEstimatedBytesMax;

    @Field(name = "sessions_context_tokens_total")
    public Long sessionsContextTokensTotal;

    @Field(name = "sessions_context_tokens_p50")
    public Long sessionsContextTokensP50;

    @Field(name = "sessions_context_tokens_p90")
    public Long sessionsContextTokensP90;

    @Field(name = "sessions_inline_image_bytes_total")
    public Long sessionsInlineImageBytesTotal;

    @Field(name = "sessions_idle_seconds_p50")
    public Long sessionsIdleSecondsP50;

    @Field(name = "sessions_idle_seconds_p90")
    public Long sessionsIdleSecondsP90;

    @Field(name = "session_turn_bucket_counts")
    public Map<String, Integer> sessionTurnBucketCounts;

    @Field(name = "session_turn_bucket_estimated_bytes")
    public Map<String, Long> sessionTurnBucketEstimatedBytes;

    @Field(name = "session_turn_bucket_estimated_bytes_p50")
    public Map<String, Long> sessionTurnBucketEstimatedBytesP50;

    @Field(name = "session_turn_bucket_estimated_bytes_p90")
    public Map<String, Long> sessionTurnBucketEstimatedBytesP90;

    @Field(name = "session_turn_bucket_context_tokens")
    public Map<String, Long> sessionTurnBucketContextTokens;

    @Field(name = "sessions_created_total")
    public Long sessionsCreatedTotal;

    @Field(name = "sessions_closed_total")
    public Long sessionsClosedTotal;

    @Field(name = "sessions_idle_evicted_total")
    public Long sessionsIdleEvictedTotal;

    @Field(name = "in_flight_turns")
    public Integer inFlightTurns;

    @Field(name = "turns_saturated_total")
    public Long turnsSaturated;

    @Field(name = "turns_rejected_total")
    public Long turnsRejected;

    @Field(name = "runs_active")
    public Integer runsActive;

    @Field(name = "runs_queued")
    public Integer runsQueued;

    @Field(name = "runs_rejected_total")
    public Long runsRejected;

    @Field(name = "sandboxes_live")
    public Integer sandboxesLive;

    @Field(name = "sandboxes_rejected_total")
    public Long sandboxesRejected;

    @Field(name = "span_ingest_depth")
    public Integer spanIngestDepth;

    @Field(name = "span_ingest_dropped_total")
    public Long spanIngestDropped;

    @Field(name = "claims_live")
    public Integer claimsLive;

    @Field(name = "direct_crs_live")
    public Integer directCrsLive;

    @Field(name = "creates_total")
    public Long createsTotal;

    @Field(name = "creates_failed_total")
    public Long createsFailedTotal;
}
