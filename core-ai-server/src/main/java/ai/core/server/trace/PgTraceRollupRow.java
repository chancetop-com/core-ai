package ai.core.server.trace;

import core.framework.db.Column;

/**
 * One row of the two daily rollups. The analytics rollup fills the dimension columns; the daily
 * rollup only fills user and agent.
 *
 * @author stephen
 */
public class PgTraceRollupRow {
    @Column(name = "user_id")
    public String userId;

    @Column(name = "agent_id")
    public String agentId;

    @Column(name = "agent_name")
    public String agentName;

    @Column(name = "source")
    public String source;

    @Column(name = "model")
    public String model;

    @Column(name = "provider_id")
    public String providerId;

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

    @Column(name = "avg_input_tokens")
    public Double avgInputTokens;

    @Column(name = "avg_output_tokens")
    public Double avgOutputTokens;

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

    @Column(name = "p90_cost_usd")
    public Double p90CostUsd;

    @Column(name = "session_count")
    public Long sessionCount;
}
