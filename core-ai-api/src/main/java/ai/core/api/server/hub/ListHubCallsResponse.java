package ai.core.api.server.hub;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListHubCallsResponse {
    @Property(name = "calls")
    public List<HubCallView> calls;

    /** Exact count of the filtered window (equals "calls in this window" on the first page). */
    @Property(name = "total")
    public Long total;
}
