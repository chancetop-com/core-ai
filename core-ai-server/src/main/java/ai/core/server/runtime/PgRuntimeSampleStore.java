package ai.core.server.runtime;

import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class PgRuntimeSampleStore implements RuntimeSampleStore {
    private static final String TABLE = "app.runtime_samples";
    private static final String INSERT_COLUMNS = " (id, pod, collected_at, heap_used_percent, heap_used, heap_max, "
        + "heap_before_gc, heap_before_gc_percent, heap_after_gc, heap_after_gc_percent, gc_id, gc_age_ms, "
        + "container_memory, container_memory_max, container_memory_percent, rss, sessions_active, "
        + "session_estimate_version, sessions_estimated_bytes_total, sessions_estimated_bytes_p50, "
        + "sessions_estimated_bytes_p90, sessions_estimated_bytes_p99, sessions_estimated_bytes_max, "
        + "sessions_context_tokens_total, sessions_context_tokens_p50, sessions_context_tokens_p90, "
        + "sessions_inline_image_bytes_total, sessions_idle_seconds_p50, sessions_idle_seconds_p90, "
        + "session_turn_bucket_counts, session_turn_bucket_estimated_bytes, session_turn_bucket_estimated_bytes_p50, "
        + "session_turn_bucket_estimated_bytes_p90, session_turn_bucket_context_tokens, sessions_created_total, "
        + "sessions_closed_total, sessions_idle_evicted_total, in_flight_turns, turns_saturated_total, "
        + "turns_rejected_total, runs_active, runs_queued, runs_rejected_total, sandboxes_live, "
        + "sandboxes_rejected_total, span_ingest_depth, span_ingest_dropped_total, claims_live, direct_crs_live, "
        + "creates_total, creates_failed_total) ";
    private static final String INSERT_VALUES = " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
        + "?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
        + "?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
    private static final String INSERT = "INSERT INTO " + TABLE + INSERT_COLUMNS + INSERT_VALUES;
    private static final String SELECT = "SELECT id, pod, collected_at, heap_used_percent, heap_used, heap_max, "
        + "heap_before_gc, heap_before_gc_percent, heap_after_gc, heap_after_gc_percent, gc_id, gc_age_ms, "
        + "container_memory, container_memory_max, container_memory_percent, rss, sessions_active, "
        + "session_estimate_version, sessions_estimated_bytes_total, sessions_estimated_bytes_p50, "
        + "sessions_estimated_bytes_p90, sessions_estimated_bytes_p99, sessions_estimated_bytes_max, "
        + "sessions_context_tokens_total, sessions_context_tokens_p50, sessions_context_tokens_p90, "
        + "sessions_inline_image_bytes_total, sessions_idle_seconds_p50, sessions_idle_seconds_p90, "
        + "session_turn_bucket_counts::text AS session_turn_bucket_counts, "
        + "session_turn_bucket_estimated_bytes::text AS session_turn_bucket_estimated_bytes, "
        + "session_turn_bucket_estimated_bytes_p50::text AS session_turn_bucket_estimated_bytes_p50, "
        + "session_turn_bucket_estimated_bytes_p90::text AS session_turn_bucket_estimated_bytes_p90, "
        + "session_turn_bucket_context_tokens::text AS session_turn_bucket_context_tokens, sessions_created_total, "
        + "sessions_closed_total, sessions_idle_evicted_total, in_flight_turns, turns_saturated_total, "
        + "turns_rejected_total, runs_active, runs_queued, runs_rejected_total, sandboxes_live, "
        + "sandboxes_rejected_total, span_ingest_depth, span_ingest_dropped_total, claims_live, direct_crs_live, "
        + "creates_total, creates_failed_total FROM " + TABLE;

    private static String json(Map<String, ?> value) {
        return value == null ? null : JsonSanitizer.json(JsonUtil.toJson(value));
    }

    private static <V> Map<String, V> map(String value, TypeReference<Map<String, V>> type) {
        return value == null ? null : JsonUtil.fromJson(type, value);
    }

    private static Object[] params(RuntimeSample sample) {
        return new Object[]{
            sample.id, JsonSanitizer.text(sample.pod), sample.collectedAt, sample.heapUsedPercent, sample.heapUsed,
            sample.heapMax, sample.heapBeforeGc, sample.heapBeforeGcPercent, sample.heapAfterGc, sample.heapAfterGcPercent,
            sample.gcId, sample.gcAgeMs, sample.containerMemory, sample.containerMemoryMax, sample.containerMemoryPercent,
            sample.rss, sample.sessionsActive, sample.sessionEstimateVersion, sample.sessionsEstimatedBytesTotal,
            sample.sessionsEstimatedBytesP50, sample.sessionsEstimatedBytesP90, sample.sessionsEstimatedBytesP99,
            sample.sessionsEstimatedBytesMax, sample.sessionsContextTokensTotal, sample.sessionsContextTokensP50,
            sample.sessionsContextTokensP90, sample.sessionsInlineImageBytesTotal, sample.sessionsIdleSecondsP50,
            sample.sessionsIdleSecondsP90, json(sample.sessionTurnBucketCounts), json(sample.sessionTurnBucketEstimatedBytes),
            json(sample.sessionTurnBucketEstimatedBytesP50), json(sample.sessionTurnBucketEstimatedBytesP90),
            json(sample.sessionTurnBucketContextTokens), sample.sessionsCreatedTotal, sample.sessionsClosedTotal,
            sample.sessionsIdleEvictedTotal, sample.inFlightTurns, sample.turnsSaturated, sample.turnsRejected,
            sample.runsActive, sample.runsQueued, sample.runsRejected, sample.sandboxesLive, sample.sandboxesRejected,
            sample.spanIngestDepth, sample.spanIngestDropped, sample.claimsLive, sample.directCrsLive,
            sample.createsTotal, sample.createsFailedTotal
        };
    }

    private static RuntimeSample toSample(PgRuntimeSampleRow row) {
        var sample = new RuntimeSample();
        sample.id = row.id;
        sample.pod = row.pod;
        sample.collectedAt = row.collectedAt;
        applyMemory(sample, row);
        applySessions(sample, row);
        applyRuntime(sample, row);
        return sample;
    }

    private static void applyMemory(RuntimeSample sample, PgRuntimeSampleRow row) {
        sample.heapUsedPercent = row.heapUsedPercent;
        sample.heapUsed = row.heapUsed;
        sample.heapMax = row.heapMax;
        sample.heapBeforeGc = row.heapBeforeGc;
        sample.heapBeforeGcPercent = row.heapBeforeGcPercent;
        sample.heapAfterGc = row.heapAfterGc;
        sample.heapAfterGcPercent = row.heapAfterGcPercent;
        sample.gcId = row.gcId;
        sample.gcAgeMs = row.gcAgeMs;
        sample.containerMemory = row.containerMemory;
        sample.containerMemoryMax = row.containerMemoryMax;
        sample.containerMemoryPercent = row.containerMemoryPercent;
        sample.rss = row.rss;
    }

    private static void applySessions(RuntimeSample sample, PgRuntimeSampleRow row) {
        sample.sessionsActive = row.sessionsActive;
        sample.sessionEstimateVersion = row.sessionEstimateVersion;
        sample.sessionsEstimatedBytesTotal = row.sessionsEstimatedBytesTotal;
        sample.sessionsEstimatedBytesP50 = row.sessionsEstimatedBytesP50;
        sample.sessionsEstimatedBytesP90 = row.sessionsEstimatedBytesP90;
        sample.sessionsEstimatedBytesP99 = row.sessionsEstimatedBytesP99;
        sample.sessionsEstimatedBytesMax = row.sessionsEstimatedBytesMax;
        sample.sessionsContextTokensTotal = row.sessionsContextTokensTotal;
        sample.sessionsContextTokensP50 = row.sessionsContextTokensP50;
        sample.sessionsContextTokensP90 = row.sessionsContextTokensP90;
        sample.sessionsInlineImageBytesTotal = row.sessionsInlineImageBytesTotal;
        sample.sessionsIdleSecondsP50 = row.sessionsIdleSecondsP50;
        sample.sessionsIdleSecondsP90 = row.sessionsIdleSecondsP90;
        sample.sessionTurnBucketCounts = map(row.sessionTurnBucketCounts, INT_MAP);
        sample.sessionTurnBucketEstimatedBytes = map(row.sessionTurnBucketEstimatedBytes, LONG_MAP);
        sample.sessionTurnBucketEstimatedBytesP50 = map(row.sessionTurnBucketEstimatedBytesP50, LONG_MAP);
        sample.sessionTurnBucketEstimatedBytesP90 = map(row.sessionTurnBucketEstimatedBytesP90, LONG_MAP);
        sample.sessionTurnBucketContextTokens = map(row.sessionTurnBucketContextTokens, LONG_MAP);
        sample.sessionsCreatedTotal = row.sessionsCreatedTotal;
        sample.sessionsClosedTotal = row.sessionsClosedTotal;
        sample.sessionsIdleEvictedTotal = row.sessionsIdleEvictedTotal;
    }

    private static void applyRuntime(RuntimeSample sample, PgRuntimeSampleRow row) {
        sample.inFlightTurns = row.inFlightTurns;
        sample.turnsSaturated = row.turnsSaturated;
        sample.turnsRejected = row.turnsRejected;
        sample.runsActive = row.runsActive;
        sample.runsQueued = row.runsQueued;
        sample.runsRejected = row.runsRejected;
        sample.sandboxesLive = row.sandboxesLive;
        sample.sandboxesRejected = row.sandboxesRejected;
        sample.spanIngestDepth = row.spanIngestDepth;
        sample.spanIngestDropped = row.spanIngestDropped;
        sample.claimsLive = row.claimsLive;
        sample.directCrsLive = row.directCrsLive;
        sample.createsTotal = row.createsTotal;
        sample.createsFailedTotal = row.createsFailedTotal;
    }

    private static final TypeReference<Map<String, Integer>> INT_MAP = new TypeReference<>() { };
    private static final TypeReference<Map<String, Long>> LONG_MAP = new TypeReference<>() { };

    @Inject
    Database database;

    @Override
    public void insert(RuntimeSample sample) {
        database.execute(INSERT, params(sample));
    }

    @Override
    public int bulkInsert(List<RuntimeSample> samples) {
        var batch = new ArrayList<Object[]>(samples.size());
        for (var sample : samples) {
            batch.add(params(sample));
        }
        database.batchExecute(INSERT, batch);
        return samples.size();
    }

    @Override
    public List<RuntimeSample> range(ZonedDateTime from, ZonedDateTime to, int limit) {
        return database.select(SELECT + " WHERE collected_at >= ? AND collected_at <= ? LIMIT ?",
                PgRuntimeSampleRow.class, from, to, limit)
            .stream().map(PgRuntimeSampleStore::toSample).toList();
    }
}
