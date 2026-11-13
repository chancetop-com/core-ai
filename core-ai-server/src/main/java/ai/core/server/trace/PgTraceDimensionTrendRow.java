package ai.core.server.trace;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgTraceDimensionTrendRow {
    @Column(name = "dim")
    public String dim;

    @Column(name = "bucket")
    public ZonedDateTime bucket;

    @Column(name = "input_tokens")
    public Long inputTokens;

    @Column(name = "output_tokens")
    public Long outputTokens;

    @Column(name = "cached_tokens")
    public Long cachedTokens;

    @Column(name = "call_count")
    public Long callCount;

    @Column(name = "cost_usd")
    public Double costUsd;
}
