package ai.core.api.server;

import ai.core.api.server.agenthub.AgentHubDetail;
import ai.core.api.server.agenthub.AgentHubLookupRequest;
import ai.core.api.server.agenthub.AgentHubLookupResponse;
import ai.core.api.server.agenthub.AgentHubReplyRequest;
import ai.core.api.server.agenthub.AgentHubRunRequest;
import ai.core.api.server.agenthub.AgentHubRunResult;
import ai.core.api.server.agenthub.AgentHubSearchRequest;
import ai.core.api.server.agenthub.AgentHubSearchResponse;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * Agent Hub surface for non-agent consumers (CLI, scripts, external agents): search the visible
 * agent catalog and run an agent as an A2A task. Execution semantics are unified — an AGENT runs
 * as a server session (continue it with {@code context_id}) and an LLM_CALL runs statelessly.
 * Only capability summaries are exposed: never system prompts, models or tool details.
 *
 * @author stephen
 */
public interface AgentHubWebService {
    @GET
    @Path("/api/hub/agents")
    AgentHubSearchResponse search(AgentHubSearchRequest request);

    @GET
    @Path("/api/hub/agents/lookup")
    AgentHubLookupResponse lookup(AgentHubLookupRequest request);

    @GET
    @Path("/api/hub/agents/:id")
    AgentHubDetail get(@PathParam("id") String id);

    @POST
    @Path("/api/hub/agents/:id/run")
    AgentHubRunResult run(@PathParam("id") String id, AgentHubRunRequest request);

    @GET
    @Path("/api/hub/agents/runs/:taskId")
    AgentHubRunResult status(@PathParam("taskId") String taskId);

    @POST
    @Path("/api/hub/agents/runs/:taskId/reply")
    AgentHubRunResult reply(@PathParam("taskId") String taskId, AgentHubReplyRequest request);

    @POST
    @Path("/api/hub/agents/runs/:taskId/cancel")
    AgentHubRunResult cancel(@PathParam("taskId") String taskId);
}
