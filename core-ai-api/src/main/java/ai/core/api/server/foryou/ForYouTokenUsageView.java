package ai.core.api.server.foryou;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ForYouTokenUsageView {
    @Property(name = "total_input_tokens")
    public Long totalInputTokens;

    @Property(name = "total_output_tokens")
    public Long totalOutputTokens;

    @Property(name = "total_tokens")
    public Long totalTokens;

    @Property(name = "total_cached_tokens")
    public Long totalCachedTokens;

    @Property(name = "total_cost_usd")
    public Double totalCostUsd;

    @Property(name = "daily")
    public List<ForYouDailyUsageView> daily;
}
