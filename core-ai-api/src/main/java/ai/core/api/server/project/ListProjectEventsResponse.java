package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListProjectEventsResponse {
    @Property(name = "events")
    public List<ProjectEventView> events;
}
