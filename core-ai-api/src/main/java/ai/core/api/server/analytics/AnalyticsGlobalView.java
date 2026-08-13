package ai.core.api.server.analytics;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class AnalyticsGlobalView {
    @Property(name = "totalInputTokens")
    public Long totalInputTokens;

    @Property(name = "totalOutputTokens")
    public Long totalOutputTokens;

    @Property(name = "totalTokens")
    public Long totalTokens;

    @Property(name = "totalCachedTokens")
    public Long totalCachedTokens;

    @Property(name = "totalCostUsd")
    public Double totalCostUsd;

    @Property(name = "totalCalls")
    public Long totalCalls;

    @Property(name = "avgTokensPerCall")
    public Double avgTokensPerCall;

    @Property(name = "avgCostPerCall")
    public Double avgCostPerCall;

    @Property(name = "maxTokensPerCall")
    public Long maxTokensPerCall;

    @Property(name = "maxCostPerCall")
    public Double maxCostPerCall;

    @Property(name = "p90TokensPerCall")
    public Double p90TokensPerCall;

    @Property(name = "prevTotalTokens")
    public Long prevTotalTokens;

    @Property(name = "prevTotalCostUsd")
    public Double prevTotalCostUsd;
}
