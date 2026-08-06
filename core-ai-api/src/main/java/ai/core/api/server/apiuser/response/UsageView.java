package ai.core.api.server.apiuser.response;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class UsageView {
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

    @Property(name = "by_day")
    public List<DailyUsageView> byDay;
}
