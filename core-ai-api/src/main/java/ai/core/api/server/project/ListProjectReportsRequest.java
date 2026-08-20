package ai.core.api.server.project;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListProjectReportsRequest {
    @QueryParam(name = "subject_id")
    public String subjectId;

    @QueryParam(name = "agent_id")
    public String agentId;
}
