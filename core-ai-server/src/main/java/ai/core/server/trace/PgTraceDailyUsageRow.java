package ai.core.server.trace;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgTraceDailyUsageRow {
    @Column(name = "date")
    public ZonedDateTime date;

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
}
