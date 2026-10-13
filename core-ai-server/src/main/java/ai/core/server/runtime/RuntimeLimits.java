package ai.core.server.runtime;

import ai.core.server.domain.SystemSettings;

/**
 * Resolved runtime governance limits. Every value is optional in the settings document; an unset
 * field keeps the shipped default so legacy documents behave like a fresh deployment. A zero on a
 * count limit means "unlimited" — the limits protect the platform, they do not describe intent.
 *
 * @author stephen
 */
public class RuntimeLimits {
    public static final int DEFAULT_HEAP_WATERMARK_PERCENT = 85;
    public static final int DEFAULT_MAX_IN_FLIGHT_TURNS_PER_POD = 200;
    public static final int DEFAULT_MAX_SESSIONS_PER_POD = 500;
    public static final int DEFAULT_MAX_SESSIONS_PER_USER = 20;
    public static final int DEFAULT_MAX_CONCURRENT_TURNS_PER_USER = 3;
    public static final int DEFAULT_MAX_QUEUED_MESSAGES_PER_SESSION = 100;
    public static final int DEFAULT_TURN_TIMEOUT_SECONDS = 3600;
    public static final int DEFAULT_SSE_MAX_QUEUE_DEPTH = 1000;
    public static final long DEFAULT_SSE_MAX_QUEUE_BYTES = 8L * 1024 * 1024;
    public static final int DEFAULT_RUN_QUEUE_CAPACITY = 100;
    public static final int DEFAULT_MAX_SANDBOXES_PER_POD = 50;
    public static final int DEFAULT_MAX_SANDBOXES_PER_USER = 10;
    public static final int DEFAULT_MAX_SANDBOX_MEMORY_MB_PER_USER = 10240;
    public static final int DEFAULT_SPAN_INGEST_QUEUE_CAPACITY = 10000;

    public static RuntimeLimits resolve(SystemSettings entity) {
        var limits = new RuntimeLimits();
        if (entity == null) return limits;
        limits.admissionEnabled = Boolean.TRUE.equals(entity.runtimeAdmissionEnabled);
        limits.heapWatermarkPercent = bounded(entity.runtimeHeapWatermarkPercent, 50, 99, DEFAULT_HEAP_WATERMARK_PERCENT);
        limits.maxInFlightTurnsPerPod = positive(entity.runtimeMaxInFlightTurnsPerPod, DEFAULT_MAX_IN_FLIGHT_TURNS_PER_POD);
        limits.maxInFlightTurnsGlobal = nonNegative(entity.runtimeMaxInFlightTurnsGlobal);
        limits.maxSessionsPerPod = positive(entity.runtimeMaxSessionsPerPod, DEFAULT_MAX_SESSIONS_PER_POD);
        limits.maxSessionsPerUser = positive(entity.runtimeMaxSessionsPerUser, DEFAULT_MAX_SESSIONS_PER_USER);
        limits.maxConcurrentTurnsPerUser = positive(entity.runtimeMaxConcurrentTurnsPerUser, DEFAULT_MAX_CONCURRENT_TURNS_PER_USER);
        limits.maxQueuedMessagesPerSession = positive(entity.runtimeMaxQueuedMessagesPerSession, DEFAULT_MAX_QUEUED_MESSAGES_PER_SESSION);
        limits.turnTimeoutSeconds = nonNegative(entity.runtimeTurnTimeoutSeconds, DEFAULT_TURN_TIMEOUT_SECONDS);
        limits.sseMaxQueueDepth = positive(entity.runtimeSseMaxQueueDepth, DEFAULT_SSE_MAX_QUEUE_DEPTH);
        limits.sseMaxQueueBytes = entity.runtimeSseMaxQueueBytes == null || entity.runtimeSseMaxQueueBytes <= 0
                ? DEFAULT_SSE_MAX_QUEUE_BYTES : entity.runtimeSseMaxQueueBytes;
        limits.runQueueCapacity = positive(entity.runtimeRunQueueCapacity, DEFAULT_RUN_QUEUE_CAPACITY);
        limits.maxSandboxesPerPod = positive(entity.runtimeMaxSandboxesPerPod, DEFAULT_MAX_SANDBOXES_PER_POD);
        limits.maxSandboxesPerUser = positive(entity.runtimeMaxSandboxesPerUser, DEFAULT_MAX_SANDBOXES_PER_USER);
        limits.maxSandboxMemoryMbPerUser = positive(entity.runtimeMaxSandboxMemoryMbPerUser, DEFAULT_MAX_SANDBOX_MEMORY_MB_PER_USER);
        limits.maxSandboxesPerAgent = nonNegative(entity.runtimeMaxSandboxesPerAgent);
        limits.spanIngestQueueCapacity = positive(entity.runtimeSpanIngestQueueCapacity, DEFAULT_SPAN_INGEST_QUEUE_CAPACITY);
        return limits;
    }

    /** Changes here rebuild the cached instance in {@link RuntimeLimitsProvider}. */
    public static String fingerprint(SystemSettings entity) {
        if (entity == null) return "";
        return String.join("|",
                String.valueOf(entity.runtimeAdmissionEnabled),
                String.valueOf(entity.runtimeHeapWatermarkPercent),
                String.valueOf(entity.runtimeMaxInFlightTurnsPerPod),
                String.valueOf(entity.runtimeMaxInFlightTurnsGlobal),
                String.valueOf(entity.runtimeMaxSessionsPerPod),
                String.valueOf(entity.runtimeMaxSessionsPerUser),
                String.valueOf(entity.runtimeMaxConcurrentTurnsPerUser),
                String.valueOf(entity.runtimeMaxQueuedMessagesPerSession),
                String.valueOf(entity.runtimeTurnTimeoutSeconds),
                String.valueOf(entity.runtimeSseMaxQueueDepth),
                String.valueOf(entity.runtimeSseMaxQueueBytes),
                String.valueOf(entity.runtimeRunQueueCapacity),
                String.valueOf(entity.runtimeMaxSandboxesPerPod),
                String.valueOf(entity.runtimeMaxSandboxesPerUser),
                String.valueOf(entity.runtimeMaxSandboxMemoryMbPerUser),
                String.valueOf(entity.runtimeMaxSandboxesPerAgent),
                String.valueOf(entity.runtimeSpanIngestQueueCapacity));
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static int nonNegative(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private static int nonNegative(Integer value, int fallback) {
        return value == null || value < 0 ? fallback : value;
    }

    private static int bounded(Integer value, int min, int max, int fallback) {
        if (value == null) return fallback;
        return Math.min(Math.max(value, min), max);
    }

    /** false = observe only: saturated requests are counted and logged, never rejected. */
    public boolean admissionEnabled;

    public int heapWatermarkPercent = DEFAULT_HEAP_WATERMARK_PERCENT;
    public int maxInFlightTurnsPerPod = DEFAULT_MAX_IN_FLIGHT_TURNS_PER_POD;
    /** 0 = no cross-pod limit. */
    public int maxInFlightTurnsGlobal;
    public int maxSessionsPerPod = DEFAULT_MAX_SESSIONS_PER_POD;
    public int maxSessionsPerUser = DEFAULT_MAX_SESSIONS_PER_USER;
    public int maxConcurrentTurnsPerUser = DEFAULT_MAX_CONCURRENT_TURNS_PER_USER;
    public int maxQueuedMessagesPerSession = DEFAULT_MAX_QUEUED_MESSAGES_PER_SESSION;
    /** 0 = turns are not bounded by wall clock. */
    public int turnTimeoutSeconds = DEFAULT_TURN_TIMEOUT_SECONDS;
    public int sseMaxQueueDepth = DEFAULT_SSE_MAX_QUEUE_DEPTH;
    public long sseMaxQueueBytes = DEFAULT_SSE_MAX_QUEUE_BYTES;
    public int runQueueCapacity = DEFAULT_RUN_QUEUE_CAPACITY;
    public int maxSandboxesPerPod = DEFAULT_MAX_SANDBOXES_PER_POD;
    public int maxSandboxesPerUser = DEFAULT_MAX_SANDBOXES_PER_USER;
    public int maxSandboxMemoryMbPerUser = DEFAULT_MAX_SANDBOX_MEMORY_MB_PER_USER;
    /** 0 = no per-agent limit. */
    public int maxSandboxesPerAgent;
    public int spanIngestQueueCapacity = DEFAULT_SPAN_INGEST_QUEUE_CAPACITY;
}
