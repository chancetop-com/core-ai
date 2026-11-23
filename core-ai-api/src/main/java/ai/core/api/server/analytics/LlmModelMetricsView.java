package ai.core.api.server.analytics;

import core.framework.api.json.Property;

/**
 * One model's LLM call metrics over the requested window. Latency and TTFT are milliseconds;
 * {@code avgOutputTokensPerSecond} is the decode speed after the first token.
 *
 * @author stephen
 */
public class LlmModelMetricsView {
    @Property(name = "model")
    public String model;

    @Property(name = "calls")
    public Long calls;

    @Property(name = "errors")
    public Long errors;

    /** Calls that carry a TTFT measurement; 0 for traffic recorded before the measurement existed. */
    @Property(name = "callsWithTtft")
    public Long callsWithTtft;

    @Property(name = "inputTokens")
    public Long inputTokens;

    @Property(name = "outputTokens")
    public Long outputTokens;

    @Property(name = "cachedTokens")
    public Long cachedTokens;

    @Property(name = "costUsd")
    public Double costUsd;

    @Property(name = "avgTtftMs")
    public Long avgTtftMs;

    @Property(name = "p50TtftMs")
    public Long p50TtftMs;

    @Property(name = "p95TtftMs")
    public Long p95TtftMs;

    @Property(name = "avgDurationMs")
    public Long avgDurationMs;

    @Property(name = "p50DurationMs")
    public Long p50DurationMs;

    @Property(name = "p95DurationMs")
    public Long p95DurationMs;

    @Property(name = "avgOutputTokensPerSecond")
    public Double avgOutputTokensPerSecond;
}

