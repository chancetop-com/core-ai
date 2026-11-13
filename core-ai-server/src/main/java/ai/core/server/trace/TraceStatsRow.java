package ai.core.server.trace;

/**
 * One row of a realtime trace rollup. The global summary leaves {@code key} null; a dimension rollup
 * fills it with the dimension value. {@code p90TotalTokens} mirrors the historical behaviour of the
 * realtime path, where it is the maximum rather than a percentile.
 *
 * @author stephen
 */
public class TraceStatsRow {
    public String key;
    public String agentName;
    public String providerName;

    public Long inputTokens;
    public Long outputTokens;
    public Long totalTokens;
    public Long cachedTokens;
    public Long callCount;
    public Double costUsd;

    public Double avgTotalTokens;
    public Double avgCostUsd;
    public Long maxTotalTokens;
    public Double maxCostUsd;
    public Double p90TotalTokens;
}
