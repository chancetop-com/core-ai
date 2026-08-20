package ai.core.api.server.project;

import core.framework.api.web.service.QueryParam;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class ListProjectEventsRequest {
    @QueryParam(name = "subject_id")
    public String subjectId;

    @QueryParam(name = "type")
    public String type;   // phase | summary | kpi | action_item | note | subject_status

    @QueryParam(name = "from")
    public ZonedDateTime from;

    @QueryParam(name = "to")
    public ZonedDateTime to;
}
