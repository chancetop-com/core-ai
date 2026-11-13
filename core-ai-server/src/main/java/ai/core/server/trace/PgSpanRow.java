package ai.core.server.trace;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgSpanRow {
    @Column(name = "id")
    public String id;

    @Column(name = "trace_id")
    public String traceId;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "span_id")
    public String spanId;

    @Column(name = "parent_span_id")
    public String parentSpanId;

    @Column(name = "name")
    public String name;

    @Column(name = "type")
    public String type;

    @Column(name = "model")
    public String model;

    @Column(name = "status")
    public String status;

    @Column(name = "tool_call_id")
    public String toolCallId;

    @Column(name = "input")
    public String input;

    @Column(name = "output")
    public String output;

    @Column(name = "input_tokens")
    public Long inputTokens;

    @Column(name = "output_tokens")
    public Long outputTokens;

    @Column(name = "cached_tokens")
    public Long cachedTokens;

    @Column(name = "cost_usd")
    public Double costUsd;

    @Column(name = "cost_source")
    public String costSource;

    @Column(name = "pricing_model_id")
    public String pricingModelId;

    @Column(name = "input_price_per_1m_tokens")
    public Double inputPricePer1MTokens;

    @Column(name = "output_price_per_1m_tokens")
    public Double outputPricePer1MTokens;

    @Column(name = "duration_ms")
    public Long durationMs;

    @Column(name = "error_message")
    public String errorMessage;

    @Column(name = "attributes")
    public String attributes;

    @Column(name = "started_at")
    public ZonedDateTime startedAt;

    @Column(name = "completed_at")
    public ZonedDateTime completedAt;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;
}
