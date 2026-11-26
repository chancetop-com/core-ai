package ai.core.server.trace;

/**
 * Per-model LLM observability metrics over a window, read from {@code app.spans} (type {@code LLM}).
 * {@code callsWithTtft} tells how much of the window carries the measurement: spans recorded before
 * TTFT existed have none, so an average over a mostly-unmeasured model is not comparable.
 *
 * @author stephen
 */
public class LlmModelMetricsRow {
    public String model;
    public Long calls;
    public Long errors;
    public Long callsWithTtft;
    public Long inputTokens;
    public Long outputTokens;
    public Long cachedTokens;
    public Double costUsd;
    public Double avgTtftMs;
    public Double p50TtftMs;
    public Double p95TtftMs;
    public Double avgDurationMs;
    public Double p50DurationMs;
    public Double p95DurationMs;
    /** Answer tokens per second in the decode window, token-weighted across the window's calls. */
    public Double avgOutputTokensPerSecond;
    public Double p50OutputTokensPerSecond;
    public Double p95OutputTokensPerSecond;
}
