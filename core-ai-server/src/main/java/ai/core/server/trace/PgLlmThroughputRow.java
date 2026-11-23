package ai.core.server.trace;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgLlmThroughputRow {
    @Column(name = "bucket_start")
    public ZonedDateTime bucketStart;

    @Column(name = "calls")
    public Long calls;

    @Column(name = "errors")
    public Long errors;

    @Column(name = "input_tokens")
    public Long inputTokens;

    @Column(name = "output_tokens")
    public Long outputTokens;

    @Column(name = "cached_tokens")
    public Long cachedTokens;

    @Column(name = "cost_usd")
    public Double costUsd;
}
