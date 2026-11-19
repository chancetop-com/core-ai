package ai.core.api.server.report;

import core.framework.api.web.service.QueryParam;

/**
 * @author core-ai
 */
public class ListReportsRequest {
    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;
}
