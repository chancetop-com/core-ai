package ai.core.api.server.project;

import core.framework.api.web.service.QueryParam;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class ListProjectReportsRequest {
    @QueryParam(name = "subject_id")
    public String subjectId;

    @QueryParam(name = "agent_id")
    public String agentId;

    @QueryParam(name = "from")
    public ZonedDateTime from;

    @QueryParam(name = "to")
    public ZonedDateTime to;

    // true = only reports not yet attributed to any subject of the project (the "unassigned" bucket)
    @QueryParam(name = "unassigned")
    public Boolean unassigned;

    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;   // default 50, max 200
}
