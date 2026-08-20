package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListTimelineResponse {
    @Property(name = "entries")
    public List<TimelineEntryView> entries;
}
