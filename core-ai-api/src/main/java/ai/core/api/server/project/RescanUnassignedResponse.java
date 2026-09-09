package ai.core.api.server.project;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class RescanUnassignedResponse {
    @Property(name = "dropped")
    public Long dropped;   // scan markers cleared; the next attribution round re-offers that material
}
