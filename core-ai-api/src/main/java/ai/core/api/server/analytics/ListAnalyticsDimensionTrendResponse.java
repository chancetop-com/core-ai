package ai.core.api.server.analytics;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListAnalyticsDimensionTrendResponse {
    @Property(name = "points")
    public List<AnalyticsDimensionTrendPointView> points;
}
