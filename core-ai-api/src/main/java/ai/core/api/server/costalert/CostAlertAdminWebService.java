package ai.core.api.server.costalert;

import ai.core.api.server.costalert.request.ListCostAlertEventsRequest;
import ai.core.api.server.costalert.request.SaveCostAlertRuleRequest;
import ai.core.api.server.costalert.response.CostAlertRuleView;
import ai.core.api.server.costalert.response.ListCostAlertEventsResponse;
import ai.core.api.server.costalert.response.ListCostAlertRulesResponse;
import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;
import core.framework.api.web.service.ResponseStatus;

/**
 * Admin surface for cost alert rule management and fired alert history.
 *
 * @author stephen
 */
public interface CostAlertAdminWebService {
    @GET
    @Path("/api/admin/cost-alert-rules")
    ListCostAlertRulesResponse list();

    @POST
    @Path("/api/admin/cost-alert-rules")
    @ResponseStatus(HTTPStatus.OK)
    CostAlertRuleView create(SaveCostAlertRuleRequest request);

    @GET
    @Path("/api/admin/cost-alert-rules/:id")
    CostAlertRuleView get(@PathParam("id") String id);

    @PUT
    @Path("/api/admin/cost-alert-rules/:id")
    CostAlertRuleView update(@PathParam("id") String id, SaveCostAlertRuleRequest request);

    @DELETE
    @Path("/api/admin/cost-alert-rules/:id")
    void delete(@PathParam("id") String id);

    @GET
    @Path("/api/admin/cost-alert-events")
    ListCostAlertEventsResponse events(ListCostAlertEventsRequest request);
}
