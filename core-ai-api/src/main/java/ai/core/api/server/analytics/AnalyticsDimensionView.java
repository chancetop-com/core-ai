package ai.core.api.server.analytics;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class AnalyticsDimensionView {
    @Property(name = "items")
    public List<AnalyticsDimensionItemView> items;

    @Property(name = "totals")
    public AnalyticsGlobalView totals;
}
