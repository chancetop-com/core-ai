package ai.core.server.trace;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgTraceAggregateRow {
    @Column(name = "group_value")
    public String groupValue;

    @Column(name = "first_value")
    public String firstValue;

    @Column(name = "input_tokens")
    public Long inputTokens;

    @Column(name = "output_tokens")
    public Long outputTokens;

    @Column(name = "total_tokens")
    public Long totalTokens;

    @Column(name = "cached_tokens")
    public Long cachedTokens;

    @Column(name = "count")
    public Long count;

    @Column(name = "cost_usd")
    public Double costUsd;
}
