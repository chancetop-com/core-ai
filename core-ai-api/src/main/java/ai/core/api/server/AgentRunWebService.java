package ai.core.api.server;

import ai.core.api.server.run.AgentRunDetailView;
import ai.core.api.server.run.LLMCallRequest;
import ai.core.api.server.run.LLMCallResponse;
import ai.core.api.server.run.ListRunsRequest;
import ai.core.api.server.run.ListRunsResponse;
import ai.core.api.server.run.TriggerRunRequest;
import ai.core.api.server.run.TriggerRunResponse;
import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;
import core.framework.api.web.service.ResponseStatus;

/**
 * @author stephen
 */
public interface AgentRunWebService {
    @POST
    @Path("/api/runs/agent/:agentId/trigger")
    @ResponseStatus(HTTPStatus.ACCEPTED)
    TriggerRunResponse trigger(@PathParam("agentId") String agentId, TriggerRunRequest request);

    @GET
    @Path("/api/runs/agent/:agentId/list")
    ListRunsResponse listByAgent(@PathParam("agentId") String agentId, ListRunsRequest request);

    @GET
    @Path("/api/runs/:id")
    AgentRunDetailView get(@PathParam("id") String id);

    @POST
    @Path("/api/runs/:id/cancel")
    void cancel(@PathParam("id") String id);

    @POST
    @Path("/api/llm/:id/call")
    LLMCallResponse llmCall(@PathParam("id") String id, LLMCallRequest request);
}
