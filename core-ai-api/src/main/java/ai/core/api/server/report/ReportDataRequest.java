package ai.core.api.server.report;

import core.framework.api.web.service.QueryParam;

/**
 * @author core-ai
 */
public class ReportDataRequest {
    @QueryParam(name = "range_kind")
    public String rangeKind;

    @QueryParam(name = "days")
    public Integer days;

    @QueryParam(name = "from")
    public String from;

    @QueryParam(name = "to")
    public String to;

    @QueryParam(name = "page_size")
    public Integer pageSize;

    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "refresh")
    public Boolean refresh;
}
