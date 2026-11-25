package ai.core.server.trace;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgSpanTokenTotalsRow {
    @Column(name = "trace_id")
    public String traceId;

    @Column(name = "input_tokens")
    public Long inputTokens;

    @Column(name = "output_tokens")
    public Long outputTokens;

    @Column(name = "cached_tokens")
    public Long cachedTokens;

    @Column(name = "cost_usd")
    public Double costUsd;
}
