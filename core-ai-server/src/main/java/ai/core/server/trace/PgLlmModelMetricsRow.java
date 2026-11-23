package ai.core.server.trace;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgLlmModelMetricsRow {
    @Column(name = "model")
    public String model;

    @Column(name = "calls")
    public Long calls;

    @Column(name = "errors")
    public Long errors;

    @Column(name = "calls_with_ttft")
    public Long callsWithTtft;

    @Column(name = "input_tokens")
    public Long inputTokens;

    @Column(name = "output_tokens")
    public Long outputTokens;

    @Column(name = "cached_tokens")
    public Long cachedTokens;

    @Column(name = "cost_usd")
    public Double costUsd;

    @Column(name = "avg_ttft_ms")
    public Double avgTtftMs;

    @Column(name = "p50_ttft_ms")
    public Double p50TtftMs;

    @Column(name = "p95_ttft_ms")
    public Double p95TtftMs;

    @Column(name = "avg_duration_ms")
    public Double avgDurationMs;

    @Column(name = "p50_duration_ms")
    public Double p50DurationMs;

    @Column(name = "p95_duration_ms")
    public Double p95DurationMs;

    @Column(name = "avg_output_tokens_per_second")
    public Double avgOutputTokensPerSecond;
}
