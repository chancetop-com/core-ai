package ai.core.api.server.report;

import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * @author core-ai
 */
public interface ReportWebService {
    @GET
    @Path("/api/reports")
    ListReportsResponse list(ListReportsRequest request);

    @GET
    @Path("/api/reports/:id")
    ReportView get(@PathParam("id") String id);

    @GET
    @Path("/api/reports/:id/data")
    ReportDataView data(@PathParam("id") String id, ReportDataRequest request);

    @DELETE
    @Path("/api/reports/:id")
    DeleteReportResponse delete(@PathParam("id") String id, DeleteReportRequest request);
}
