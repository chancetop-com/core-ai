package ai.core.server.replay.domain;

import core.framework.mongo.Field;

/**
 * Usage snapshot duplicated from the source span so the compare view does not
 * need a second trace lookup (and survives trace archiving).
 *
 * @author stephen
 */
public class ReplayUsage {
    @Field(name = "input_tokens")
    public Long inputTokens;

    @Field(name = "output_tokens")
    public Long outputTokens;

    @Field(name = "cached_tokens")
    public Long cachedTokens;

    @Field(name = "cost_usd")
    public Double costUsd;

    @Field(name = "duration_ms")
    public Long durationMs;
}
