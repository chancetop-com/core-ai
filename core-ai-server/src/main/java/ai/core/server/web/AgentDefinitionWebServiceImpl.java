package ai.core.server.web;

import ai.core.api.server.agent.AgentDefinitionView;
import ai.core.api.server.AgentDefinitionWebService;
import ai.core.api.server.agent.ConvertJavaToSchemaRequest;
import ai.core.api.server.agent.ConvertJavaToSchemaResponse;
import ai.core.api.server.agent.CreateAgentFromSessionRequest;
import ai.core.api.server.agent.CreateAgentRequest;
import ai.core.api.server.agent.ListAgentsResponse;
import ai.core.api.server.agent.UpdateAgentRequest;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.agent.JavaToSchemaService;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.WebContext;

/**
 * @author stephen
 */
public class AgentDefinitionWebServiceImpl implements AgentDefinitionWebService {
    @Inject
    WebContext webContext;
    @Inject
    AgentDefinitionService agentDefinitionService;
    @Inject
    JavaToSchemaService javaToSchemaService;

    @Override
    public AgentDefinitionView create(CreateAgentRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        return agentDefinitionService.create(request, userId);
    }

    @Override
    public ListAgentsResponse list() {
        var userId = AuthContext.userId(webContext);
        return agentDefinitionService.list(userId);
    }

    @Override
    public AgentDefinitionView get(String id) {
        return agentDefinitionService.get(id);
    }

    @Override
    public AgentDefinitionView getByName(String name) {
        var userId = AuthContext.userId(webContext);
        return agentDefinitionService.getByName(name, userId);
    }

    @Override
    public AgentDefinitionView update(String id, UpdateAgentRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        return agentDefinitionService.update(id, request);
    }

    @Override
    public AgentDefinitionView publish(String id) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        return agentDefinitionService.publish(id);
    }

    @Override
    public AgentDefinitionView createFromSession(CreateAgentFromSessionRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        return agentDefinitionService.createFromSession(request, userId);
    }

    @Override
    public AgentDefinitionView enableWebhook(String id) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        return agentDefinitionService.enableWebhook(id);
    }

    @Override
    public AgentDefinitionView disableWebhook(String id) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        return agentDefinitionService.disableWebhook(id);
    }

    @Override
    public void delete(String id) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        agentDefinitionService.delete(id);
    }

    @Override
    public ConvertJavaToSchemaResponse convertJavaToSchema(ConvertJavaToSchemaRequest request) {
        return javaToSchemaService.convert(request.javaCode);
    }
}
