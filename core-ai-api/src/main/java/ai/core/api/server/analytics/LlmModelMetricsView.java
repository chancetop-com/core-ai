package ai.core.api.server.analytics;

import core.framework.api.json.Property;

/**
 * One model's LLM call metrics over the requested window. Latency and TTFT are milliseconds.
 * <p>
 * The output speeds cover answer tokens only: reasoning/thinking tokens and the time spent on them are
 * excluded, and calls whose answer window is shorter than 200 ms are left out because they measure
 * chunk arrival rather than model speed. {@code avgOutputTokensPerSecond} is the token-weighted overall
 * rate, {@code p50OutputTokensPerSecond} the typical call.
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

    @Property(name = "p50OutputTokensPerSecond")
    public Double p50OutputTokensPerSecond;

    @Property(name = "p95OutputTokensPerSecond")
    public Double p95OutputTokensPerSecond;
}

