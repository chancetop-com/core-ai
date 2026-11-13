package ai.core.server.trace;

/**
 * One bucket of the daily trace rollup. The daily job groups by user and agent; the analytics job
 * groups by user, agent, source, model and provider. Null metrics are the ones the bucket did not ask
 * for, so one row type serves both.
 *
 * @author stephen
 */
public class TraceDailyRollupRow {
    public String userId;
    public String agentId;
    public String agentName;
    public String source;
    public String model;
    public String providerId;
    public String providerName;

    public Long inputTokens;
    public Long outputTokens;
    public Long totalTokens;
    public Long cachedTokens;
    public Long callCount;
    public Double costUsd;

    public Double avgInputTokens;
    public Double avgOutputTokens;
    public Double avgTotalTokens;
    public Double avgCostUsd;
    public Long maxTotalTokens;
    public Double maxCostUsd;

    public Double p90TotalTokens;
    public Double p90CostUsd;
    public Long sessionCount;

}
