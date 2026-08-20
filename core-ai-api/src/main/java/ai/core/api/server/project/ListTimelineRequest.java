package ai.core.api.server.project;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListTimelineRequest {
    @QueryParam(name = "subject_id")
    public String subjectId;
}
