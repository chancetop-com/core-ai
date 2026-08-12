package ai.core.api.server.costalert.request;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListCostAlertEventsRequest {
    @QueryParam(name = "ruleId")
    public String ruleId;

    @QueryParam(name = "dateFrom")
    public String dateFrom;

    @QueryParam(name = "dateTo")
    public String dateTo;

    @QueryParam(name = "limit")
    public Integer limit;
}
