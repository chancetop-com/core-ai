package ai.core.api.server.runtime;

import core.framework.api.json.Property;

/**
 * Effective runtime governance limits (what the pods are actually enforcing right now).
 *
 * @author stephen
 */
public class RuntimeLimitsView {
    @Property(name = "admission_enabled")
    public Boolean admissionEnabled;

    @Property(name = "heap_watermark_percent")
    public Integer heapWatermarkPercent;

    @Property(name = "max_in_flight_turns_per_pod")
    public Integer maxInFlightTurnsPerPod;

    @Property(name = "max_in_flight_turns_global")
    public Integer maxInFlightTurnsGlobal;

    @Property(name = "max_sessions_per_pod")
    public Integer maxSessionsPerPod;

    @Property(name = "max_sessions_per_user")
    public Integer maxSessionsPerUser;

    @Property(name = "max_concurrent_turns_per_user")
    public Integer maxConcurrentTurnsPerUser;

    @Property(name = "max_queued_messages_per_session")
    public Integer maxQueuedMessagesPerSession;

    @Property(name = "turn_timeout_seconds")
    public Integer turnTimeoutSeconds;

    @Property(name = "sse_max_queue_depth")
    public Integer sseMaxQueueDepth;

    @Property(name = "sse_max_queue_bytes")
    public Long sseMaxQueueBytes;

    @Property(name = "run_queue_capacity")
    public Integer runQueueCapacity;

    @Property(name = "max_sandboxes_per_pod")
    public Integer maxSandboxesPerPod;

    @Property(name = "max_sandboxes_per_user")
    public Integer maxSandboxesPerUser;

    @Property(name = "max_sandbox_memory_mb_per_user")
    public Integer maxSandboxMemoryMbPerUser;

    @Property(name = "max_sandboxes_per_agent")
    public Integer maxSandboxesPerAgent;

    @Property(name = "span_ingest_queue_capacity")
    public Integer spanIngestQueueCapacity;

    @Property(name = "updated_by")
    public String updatedBy;

    @Property(name = "updated_at")
    public java.time.ZonedDateTime updatedAt;
}
