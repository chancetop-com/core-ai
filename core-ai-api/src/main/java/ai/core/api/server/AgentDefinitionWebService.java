package ai.core.api.server;

import ai.core.api.server.agent.AgentDefinitionView;
import ai.core.api.server.agent.ConvertJavaToSchemaRequest;
import ai.core.api.server.agent.ConvertJavaToSchemaResponse;
import ai.core.api.server.agent.CreateAgentFromSessionRequest;
import ai.core.api.server.agent.CreateAgentRequest;
import ai.core.api.server.agent.ListAgentsRequest;
import ai.core.api.server.agent.ListAgentsResponse;
import ai.core.api.server.agent.UpdateAgentRequest;
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
public interface AgentDefinitionWebService {
    @POST
    @Path("/api/agents")
    @ResponseStatus(HTTPStatus.CREATED)
    AgentDefinitionView create(CreateAgentRequest request);

    @GET
    @Path("/api/agents")
    ListAgentsResponse list(ListAgentsRequest request);

    @GET
    @Path("/api/agents/:id")
    AgentDefinitionView get(@PathParam("id") String id);

    @GET
    @Path("/api/agents/name/:name")
    AgentDefinitionView getByName(@PathParam("name") String name);

    @PUT
    @Path("/api/agents/:id")
    AgentDefinitionView update(@PathParam("id") String id, UpdateAgentRequest request);

    @POST
    @Path("/api/agents/:id/publish")
    AgentDefinitionView publish(@PathParam("id") String id);

    @POST
    @Path("/api/agents/from-session")
    @ResponseStatus(HTTPStatus.CREATED)
    AgentDefinitionView createFromSession(CreateAgentFromSessionRequest request);

    @POST
    @Path("/api/agents/:id/webhook/enable")
    AgentDefinitionView enableWebhook(@PathParam("id") String id);

    @POST
    @Path("/api/agents/:id/webhook/disable")
    AgentDefinitionView disableWebhook(@PathParam("id") String id);

    @DELETE
    @Path("/api/agents/:id")
    void delete(@PathParam("id") String id);

    @POST
    @Path("/api/utils/java-to-schema")
    ConvertJavaToSchemaResponse convertJavaToSchema(ConvertJavaToSchemaRequest request);
}
