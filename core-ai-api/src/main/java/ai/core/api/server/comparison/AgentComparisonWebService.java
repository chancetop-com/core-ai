package ai.core.api.server.comparison;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * Agent version comparison test (online A/B): two frozen config variants of one agent run as a
 * session pair, testers vote per turn, the owner adopts the winner through the normal publish flow.
 *
 * @author stephen
 */
public interface AgentComparisonWebService {
    @POST
    @Path("/api/agent-comparisons")
    AgentComparisonView create(CreateAgentComparisonRequest request);

    @GET
    @Path("/api/agent-comparisons")
    ListAgentComparisonsResponse list(ListAgentComparisonsRequest request);

    @GET
    @Path("/api/agent-comparisons/agent/:agentId/running")
    AgentComparisonView running(@PathParam("agentId") String agentId);

    @GET
    @Path("/api/agent-comparisons/:id")
    AgentComparisonView get(@PathParam("id") String id);

    @PUT
    @Path("/api/agent-comparisons/:id")
    AgentComparisonView update(@PathParam("id") String id, UpdateAgentComparisonRequest request);

    @POST
    @Path("/api/agent-comparisons/:id/start")
    AgentComparisonView start(@PathParam("id") String id, StartAgentComparisonRequest request);

    @POST
    @Path("/api/agent-comparisons/:id/sessions")
    CreateComparisonSessionsResponse createSessions(@PathParam("id") String id);

    @GET
    @Path("/api/agent-comparisons/by-session/:sessionId")
    ComparisonSessionContextView bySession(@PathParam("sessionId") String sessionId);

    @POST
    @Path("/api/agent-comparisons/:id/votes")
    ComparisonVoteView vote(@PathParam("id") String id, SubmitComparisonVoteRequest request);

    @POST
    @Path("/api/agent-comparisons/:id/end")
    AgentComparisonView end(@PathParam("id") String id, EndAgentComparisonRequest request);
}
