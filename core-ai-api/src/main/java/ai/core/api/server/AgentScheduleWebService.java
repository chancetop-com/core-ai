package ai.core.api.server;

import ai.core.api.server.run.TriggerRunResponse;
import ai.core.api.server.schedule.AgentScheduleView;
import ai.core.api.server.schedule.CreateScheduleRequest;
import ai.core.api.server.schedule.ListSchedulesResponse;
import ai.core.api.server.schedule.ListSessionSchedulesRequest;
import ai.core.api.server.schedule.ListSessionSchedulesResponse;
import ai.core.api.server.schedule.SessionScheduleView;
import ai.core.api.server.schedule.UpdateScheduleRequest;
import ai.core.api.server.schedule.UpdateSessionScheduleRequest;
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

    @GET
    @Path("/api/schedules/sessions")
    ListSessionSchedulesResponse listSessionSchedules(ListSessionSchedulesRequest request);

    @PUT
    @Path("/api/schedules/sessions/:id")
    SessionScheduleView updateSessionSchedule(@PathParam("id") String id, UpdateSessionScheduleRequest request);

    @PUT
    @Path("/api/schedules/:id")
    AgentScheduleView update(@PathParam("id") String id, UpdateScheduleRequest request);

    @DELETE
    @Path("/api/schedules/:id")
    void delete(@PathParam("id") String id);

    @POST
    @Path("/api/schedules/:id/trigger")
    TriggerRunResponse trigger(@PathParam("id") String id);
}
