package ai.core.api.server;

import ai.core.api.server.schedule.AgentScheduleView;
import ai.core.api.server.schedule.CreateScheduleRequest;
import ai.core.api.server.schedule.ListSchedulesResponse;
import ai.core.api.server.schedule.UpdateScheduleRequest;
import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;
import core.framework.api.web.service.ResponseStatus;

/**
 * @author stephen
 */
public interface AgentScheduleWebService {
    @POST
    @Path("/api/schedules")
    @ResponseStatus(HTTPStatus.CREATED)
    AgentScheduleView create(CreateScheduleRequest request);

    @GET
    @Path("/api/schedules")
    ListSchedulesResponse list();

    @GET
    @Path("/api/schedules/agent/:agentId/list")
    ListSchedulesResponse listByAgent(@PathParam("agentId") String agentId);

    @PUT
    @Path("/api/schedules/:id")
    AgentScheduleView update(@PathParam("id") String id, UpdateScheduleRequest request);

    @DELETE
    @Path("/api/schedules/:id")
    void delete(@PathParam("id") String id);
}
