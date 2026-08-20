package ai.core.api.server.project;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class GetProjectStatsRequest {
    @QueryParam(name = "subject_id")
    public String subjectId;
}
