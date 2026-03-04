package ai.core.api.server.schedule;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListSchedulesResponse {
    @Property(name = "schedules")
    public List<AgentScheduleView> schedules;
}
