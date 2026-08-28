package ai.core.api.server.replay;

import core.framework.api.json.Property;

/**
 * Token/cost/duration snapshot of the original span or a replay sample.
 *
 * @author stephen
 */
public class ReplayUsageView {
    @Property(name = "input_tokens")
    public Long inputTokens;

    @Property(name = "output_tokens")
    public Long outputTokens;

    @Property(name = "cached_tokens")
    public Long cachedTokens;

    @Property(name = "cost_usd")
    public Double costUsd;

    @Property(name = "duration_ms")
    public Long durationMs;
}
