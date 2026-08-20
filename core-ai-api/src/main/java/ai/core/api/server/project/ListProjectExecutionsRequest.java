package ai.core.api.server.project;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListProjectExecutionsRequest {
    @QueryParam(name = "type")
    public String type;   // chat | run | workflow, empty = all

    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;

    @QueryParam(name = "subject_id")
    public String subjectId;
}
