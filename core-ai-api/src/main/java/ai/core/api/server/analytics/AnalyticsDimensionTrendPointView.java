package ai.core.api.server.analytics;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class AnalyticsDimensionTrendPointView {
    @Property(name = "key")
    public String key;

    @Property(name = "timestamp")
    public String timestamp;

    @Property(name = "inputTokens")
    public Long inputTokens;

    @Property(name = "outputTokens")
    public Long outputTokens;

    @Property(name = "cachedTokens")
    public Long cachedTokens;

    @Property(name = "costUsd")
    public Double costUsd;

    @Property(name = "callCount")
    public Long callCount;
}
