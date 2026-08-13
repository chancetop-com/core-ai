package ai.core.api.server.analytics;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class AnalyticsDimensionItemView {
    @Property(name = "key")
    public String key;

    @Property(name = "label")
    public String label;

    @Property(name = "inputTokens")
    public Long inputTokens;

    @Property(name = "outputTokens")
    public Long outputTokens;

    @Property(name = "totalTokens")
    public Long totalTokens;

    @Property(name = "cachedTokens")
    public Long cachedTokens;

    @Property(name = "costUsd")
    public Double costUsd;

    @Property(name = "callCount")
    public Long callCount;

    @Property(name = "avgInputTokens")
    public Double avgInputTokens;

    @Property(name = "avgOutputTokens")
    public Double avgOutputTokens;

    @Property(name = "avgTotalTokens")
    public Double avgTotalTokens;

    @Property(name = "avgCostUsd")
    public Double avgCostUsd;

    @Property(name = "maxTotalTokens")
    public Long maxTotalTokens;

    @Property(name = "maxCostUsd")
    public Double maxCostUsd;

    @Property(name = "p90TotalTokens")
    public Double p90TotalTokens;

    @Property(name = "tokenShare")
    public Double tokenShare;

    @Property(name = "costShare")
    public Double costShare;
}
