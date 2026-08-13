package ai.core.api.server.memory;

import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * @author stephen
 */
public interface AgentMemoryWebService {
    @GET
    @Path("/api/agents/:id/memories")
    ListAgentMemoriesResponse listMemories(@PathParam("id") String agentId);

    @DELETE
    @Path("/api/agents/:id/memories/:memoryId")
    void deleteMemory(@PathParam("id") String agentId, @PathParam("memoryId") String memoryId);

    @DELETE
    @Path("/api/agents/:id/memories")
    void deleteAllMemories(@PathParam("id") String agentId);

    @GET
    @Path("/api/agents/:id/memory-experiment-config")
    AgentMemoryExperimentConfigView getExperimentConfig(@PathParam("id") String agentId);

    @PUT
    @Path("/api/agents/:id/memory-experiment-config")
    AgentMemoryExperimentConfigView saveExperimentConfig(@PathParam("id") String agentId, AgentMemoryExperimentConfigView request);

    @DELETE
    @Path("/api/agents/:id/memory-experiment-config")
    void deleteExperimentConfig(@PathParam("id") String agentId);

    @GET
    @Path("/api/memory-experiments/runs")
    ListExperimentRunsResponse listRuns(ListExperimentRunsRequest request);

    @GET
    @Path("/api/memory-experiments/runs/:id")
    ExperimentRunView getRun(@PathParam("id") String id);

    @GET
    @Path("/api/memory-experiments/configs")
    ListExperimentConfigsResponse listConfigs(ListExperimentConfigsRequest request);
}
