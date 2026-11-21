package ai.core.api.server.evaluation.request;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListTrialsRequest {
    @QueryParam(name = "status")
    public String status;

    @QueryParam(name = "case_id")
    public String caseId;

    @QueryParam(name = "task_result")
    public String taskResult;

    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;
}
