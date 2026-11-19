package ai.core.api.server.report;

import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * @author core-ai
 */
public interface HomeReportWebService {
    @GET
    @Path("/api/for-you/report-pins")
    ReportPinsView layout();

    @POST
    @Path("/api/for-you/report-pins")
    ReportPinView pin(ReportPinCreateRequest request);

    @DELETE
    @Path("/api/for-you/report-pins/:id")
    DeleteReportResponse unpin(@PathParam("id") String id, DeletePinRequest request);

    @PUT
    @Path("/api/for-you/report-pins/layout")
    ReportPinsView reorder(ReportLayoutRequest request);

    @POST
    @Path("/api/for-you/report-pins/data")
    ReportPinsDataResponse data(ReportPinsDataRequest request);
}
