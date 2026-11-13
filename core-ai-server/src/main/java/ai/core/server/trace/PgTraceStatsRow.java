package ai.core.server.trace;

import core.framework.db.Column;

/**
 * @author stephen
 */
public class PgTraceStatsRow {
    @Column(name = "group_value")
    public String groupValue;

    @Column(name = "agent_name")
    public String agentName;

    @Column(name = "provider_name")
    public String providerName;

    @Column(name = "input_tokens")
    public Long inputTokens;

    @Column(name = "output_tokens")
    public Long outputTokens;

    @Column(name = "total_tokens")
    public Long totalTokens;

    @Column(name = "cached_tokens")
    public Long cachedTokens;

    @Column(name = "call_count")
    public Long callCount;

    @Column(name = "cost_usd")
    public Double costUsd;

    @Column(name = "avg_total_tokens")
    public Double avgTotalTokens;

    @Column(name = "avg_cost_usd")
    public Double avgCostUsd;

    @Column(name = "max_total_tokens")
    public Long maxTotalTokens;

    @Column(name = "max_cost_usd")
    public Double maxCostUsd;

    @Column(name = "p90_total_tokens")
    public Double p90TotalTokens;
}
