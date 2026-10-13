package ai.core.api.server.comparison;

import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * Saved agent versions: snapshots of the agent's executable config that can be edited freely, compared
 * as experiment sides, and adopted after winning.
 *
 * @author stephen
 */
public interface AgentVersionWebService {
    @POST
    @Path("/api/agent-versions")
    AgentVersionView create(CreateAgentVersionRequest request);

    @GET
    @Path("/api/agent-versions")
    ListAgentVersionsResponse list(ListAgentVersionsRequest request);

    @GET
    @Path("/api/agent-versions/:id")
    AgentVersionView get(@PathParam("id") String id);

    @PUT
    @Path("/api/agent-versions/:id")
    AgentVersionView update(@PathParam("id") String id, UpdateAgentVersionRequest request);

    @DELETE
    @Path("/api/agent-versions/:id")
    void delete(@PathParam("id") String id);
}
