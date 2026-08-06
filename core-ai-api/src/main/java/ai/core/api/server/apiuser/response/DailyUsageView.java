package ai.core.api.server.apiuser.response;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class DailyUsageView {
    @Property(name = "date")
    public String date;

    @Property(name = "total_tokens")
    public Long totalTokens;

    @Property(name = "input_tokens")
    public Long inputTokens;

    @Property(name = "output_tokens")
    public Long outputTokens;

    @Property(name = "cached_tokens")
    public Long cachedTokens;

    @Property(name = "cost_usd")
    public Double costUsd;

    @Property(name = "call_count")
    public Long callCount;
}
