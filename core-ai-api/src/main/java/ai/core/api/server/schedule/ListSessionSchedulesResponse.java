package ai.core.api.server.schedule;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListSessionSchedulesResponse {
    @Property(name = "session_schedules")
    public List<SessionScheduleView> sessionSchedules;

    @Property(name = "total")
    public Long total;
}
