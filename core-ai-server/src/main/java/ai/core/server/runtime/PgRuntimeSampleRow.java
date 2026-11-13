package ai.core.server.runtime;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * The turn-bucket maps stay JSON text here: core-ng's json columns only accept beans or lists, so the
 * store converts them with Jackson like it does for trace metadata.
 *
 * @author stephen
 */
public class PgRuntimeSampleRow {
    @Column(name = "id")
    public String id;

    @Column(name = "pod")
    public String pod;

    @Column(name = "collected_at")
    public ZonedDateTime collectedAt;

    @Column(name = "heap_used_percent")
    public Double heapUsedPercent;

    @Column(name = "heap_used")
    public Long heapUsed;

    @Column(name = "heap_max")
    public Long heapMax;

    @Column(name = "heap_before_gc")
    public Long heapBeforeGc;

    @Column(name = "heap_before_gc_percent")
    public Double heapBeforeGcPercent;

    @Column(name = "heap_after_gc")
    public Long heapAfterGc;

    @Column(name = "heap_after_gc_percent")
    public Double heapAfterGcPercent;

    @Column(name = "gc_id")
    public Long gcId;

    @Column(name = "gc_age_ms")
    public Long gcAgeMs;

    @Column(name = "container_memory")
    public Long containerMemory;

    @Column(name = "container_memory_max")
    public Long containerMemoryMax;

    @Column(name = "container_memory_percent")
    public Double containerMemoryPercent;

    @Column(name = "rss")
    public Long rss;

    @Column(name = "sessions_active")
    public Integer sessionsActive;

    @Column(name = "session_estimate_version")
    public Integer sessionEstimateVersion;

    @Column(name = "sessions_estimated_bytes_total")
    public Long sessionsEstimatedBytesTotal;

    @Column(name = "sessions_estimated_bytes_p50")
    public Long sessionsEstimatedBytesP50;

    @Column(name = "sessions_estimated_bytes_p90")
    public Long sessionsEstimatedBytesP90;

    @Column(name = "sessions_estimated_bytes_p99")
    public Long sessionsEstimatedBytesP99;

    @Column(name = "sessions_estimated_bytes_max")
    public Long sessionsEstimatedBytesMax;

    @Column(name = "sessions_context_tokens_total")
    public Long sessionsContextTokensTotal;

    @Column(name = "sessions_context_tokens_p50")
    public Long sessionsContextTokensP50;

    @Column(name = "sessions_context_tokens_p90")
    public Long sessionsContextTokensP90;

    @Column(name = "sessions_inline_image_bytes_total")
    public Long sessionsInlineImageBytesTotal;

    @Column(name = "sessions_idle_seconds_p50")
    public Long sessionsIdleSecondsP50;

    @Column(name = "sessions_idle_seconds_p90")
    public Long sessionsIdleSecondsP90;

    @Column(name = "session_turn_bucket_counts")
    public String sessionTurnBucketCounts;

    @Column(name = "session_turn_bucket_estimated_bytes")
    public String sessionTurnBucketEstimatedBytes;

    @Column(name = "session_turn_bucket_estimated_bytes_p50")
    public String sessionTurnBucketEstimatedBytesP50;

    @Column(name = "session_turn_bucket_estimated_bytes_p90")
    public String sessionTurnBucketEstimatedBytesP90;

    @Column(name = "session_turn_bucket_context_tokens")
    public String sessionTurnBucketContextTokens;

    @Column(name = "sessions_created_total")
    public Long sessionsCreatedTotal;

    @Column(name = "sessions_closed_total")
    public Long sessionsClosedTotal;

    @Column(name = "sessions_idle_evicted_total")
    public Long sessionsIdleEvictedTotal;

    @Column(name = "in_flight_turns")
    public Integer inFlightTurns;

    @Column(name = "turns_saturated_total")
    public Long turnsSaturated;

    @Column(name = "turns_rejected_total")
    public Long turnsRejected;

    @Column(name = "runs_active")
    public Integer runsActive;

    @Column(name = "runs_queued")
    public Integer runsQueued;

    @Column(name = "runs_rejected_total")
    public Long runsRejected;

    @Column(name = "sandboxes_live")
    public Integer sandboxesLive;

    @Column(name = "sandboxes_rejected_total")
    public Long sandboxesRejected;

    @Column(name = "span_ingest_depth")
    public Integer spanIngestDepth;

    @Column(name = "span_ingest_dropped_total")
    public Long spanIngestDropped;

    @Column(name = "claims_live")
    public Integer claimsLive;

    @Column(name = "direct_crs_live")
    public Integer directCrsLive;

    @Column(name = "creates_total")
    public Long createsTotal;

    @Column(name = "creates_failed_total")
    public Long createsFailedTotal;
}
