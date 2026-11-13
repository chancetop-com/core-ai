package ai.core.server.trace;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgTraceRow {
    @Column(name = "id")
    public String id;

    @Column(name = "trace_id")
    public String traceId;

    @Column(name = "name")
    public String name;

    @Column(name = "model")
    public String model;

    @Column(name = "type")
    public String type;

    @Column(name = "source")
    public String source;

    @Column(name = "agent_name")
    public String agentName;

    @Column(name = "agent_id")
    public String agentId;

    @Column(name = "session_id")
    public String sessionId;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "status")
    public String status;

    @Column(name = "error_message")
    public String errorMessage;

    @Column(name = "input")
    public String input;

    @Column(name = "output")
    public String output;

    @Column(name = "metadata")
    public String metadata;

    @Column(name = "input_tokens")
    public Long inputTokens;

    @Column(name = "output_tokens")
    public Long outputTokens;

    @Column(name = "total_tokens")
    public Long totalTokens;

    @Column(name = "cached_tokens")
    public Long cachedTokens;

    @Column(name = "cost_usd")
    public Double costUsd;

    @Column(name = "duration_ms")
    public Long durationMs;

    @Column(name = "started_at")
    public ZonedDateTime startedAt;

    @Column(name = "completed_at")
    public ZonedDateTime completedAt;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;
}
