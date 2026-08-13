package ai.core.api.server.analytics;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListAnalyticsTrendResponse {
    @Property(name = "points")
    public List<AnalyticsTrendPointView> points;
}
