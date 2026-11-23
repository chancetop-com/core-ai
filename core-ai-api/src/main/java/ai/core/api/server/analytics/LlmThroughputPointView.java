package ai.core.api.server.analytics;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class LlmThroughputPointView {
    @Property(name = "bucketStart")
    public String bucketStart;

    @Property(name = "calls")
    public Long calls;

    @Property(name = "errors")
    public Long errors;

    @Property(name = "inputTokens")
    public Long inputTokens;

    @Property(name = "outputTokens")
    public Long outputTokens;

    @Property(name = "cachedTokens")
    public Long cachedTokens;

    @Property(name = "costUsd")
    public Double costUsd;
}
