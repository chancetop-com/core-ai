package ai.core.api.server.costalert.response;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListCostAlertEventsResponse {
    @Property(name = "events")
    public List<CostAlertEventView> events;
}
