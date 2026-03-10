package ai.core.server.agent;

import ai.core.api.apidefinition.ApiDefinitionType;
import ai.core.api.server.agent.AgentDefinitionView;
import ai.core.api.server.agent.CreateAgentFromSessionRequest;
import ai.core.api.server.agent.CreateAgentRequest;
import ai.core.api.server.agent.ListAgentsResponse;
import ai.core.api.server.agent.UpdateAgentRequest;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.session.AgentSessionManager;
import ai.core.tool.ToolCall;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * @author stephen
 */
public class AgentDefinitionService {
    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;

    @Inject
    AgentSessionManager sessionManager;

    public AgentDefinitionView create(CreateAgentRequest request, String userId) {
        var entity = new AgentDefinition();
        entity.id = UUID.randomUUID().toString();
        entity.userId = userId;
        entity.name = request.name;
        entity.description = request.description;
        entity.systemPrompt = request.systemPrompt;
        entity.model = request.model;
        entity.temperature = request.temperature;
        entity.maxTurns = request.maxTurns != null ? request.maxTurns : 20;
        entity.timeoutSeconds = request.timeoutSeconds != null ? request.timeoutSeconds : 600;
        entity.toolIds = request.toolIds;
        entity.inputTemplate = request.inputTemplate;
        entity.variables = request.variables;
        entity.type = request.type != null ? DefinitionType.valueOf(request.type) : DefinitionType.AGENT;
        entity.responseSchema = request.responseSchema != null ? serializeResponseSchema(request.responseSchema) : null;
        entity.status = AgentStatus.DRAFT;
        entity.createdAt = ZonedDateTime.now();
        entity.updatedAt = entity.createdAt;

        agentDefinitionCollection.insert(entity);
        return toView(entity);
    }

    public ListAgentsResponse list(String userId) {
        var entities = agentDefinitionCollection.find(Filters.or(
            Filters.eq("user_id", userId),
            Filters.eq("system_default", true)
        ));
        var response = new ListAgentsResponse();
        response.agents = entities.stream().map(this::toView).toList();
        response.total = (long) response.agents.size();
        return response;
    }

    public AgentDefinitionView get(String id) {
        var entity = agentDefinitionCollection.get(id)
                .orElseThrow(() -> new RuntimeException("agent not found, id=" + id));
        return toView(entity);
    }

    public AgentDefinition getEntity(String id) {
        return agentDefinitionCollection.get(id)
                .orElseThrow(() -> new RuntimeException("agent not found, id=" + id));
    }

    public AgentDefinitionView update(String id, UpdateAgentRequest request) {
        var entity = agentDefinitionCollection.get(id)
                .orElseThrow(() -> new RuntimeException("agent not found, id=" + id));

        if (request.name != null) entity.name = request.name;
        if (request.description != null) entity.description = request.description;
        if (request.systemPrompt != null) entity.systemPrompt = request.systemPrompt;
        if (request.model != null) entity.model = request.model;
        if (request.temperature != null) entity.temperature = request.temperature;
        if (request.maxTurns != null) entity.maxTurns = request.maxTurns;
        if (request.timeoutSeconds != null) entity.timeoutSeconds = request.timeoutSeconds;
        if (request.toolIds != null) entity.toolIds = request.toolIds;
        if (request.inputTemplate != null) entity.inputTemplate = request.inputTemplate;
        if (request.variables != null) entity.variables = request.variables;
        if (request.responseSchema != null) entity.responseSchema = serializeResponseSchema(request.responseSchema);
        entity.updatedAt = ZonedDateTime.now();

        agentDefinitionCollection.replace(entity);
        return toView(entity);
    }

    public AgentDefinitionView publish(String id) {
        var entity = agentDefinitionCollection.get(id)
                .orElseThrow(() -> new RuntimeException("agent not found, id=" + id));

        var config = new AgentPublishedConfig();
        config.systemPrompt = entity.systemPrompt;
        config.model = entity.model;
        config.temperature = entity.temperature;
        config.maxTurns = entity.maxTurns;
        config.timeoutSeconds = entity.timeoutSeconds;
        config.toolIds = entity.toolIds;
        config.inputTemplate = entity.inputTemplate;
        config.variables = entity.variables;
        config.responseSchema = entity.responseSchema;

        entity.publishedConfig = config;
        entity.status = AgentStatus.PUBLISHED;
        entity.publishedAt = ZonedDateTime.now();
        entity.updatedAt = entity.publishedAt;

        agentDefinitionCollection.replace(entity);
        return toView(entity);
    }

    public AgentDefinitionView createFromSession(CreateAgentFromSessionRequest request, String userId) {
        var session = sessionManager.getSession(request.sessionId);
        var agent = session.agent();

        var entity = new AgentDefinition();
        entity.id = UUID.randomUUID().toString();
        entity.userId = userId;
        entity.name = request.name;
        entity.description = request.description;
        entity.systemPrompt = agent.getSystemPrompt();
        entity.model = agent.getModel();
        entity.temperature = agent.getTemperature();
        entity.maxTurns = 20;
        entity.timeoutSeconds = 600;
        entity.toolIds = agent.getToolCalls().stream().map(ToolCall::getName).toList();
        entity.inputTemplate = request.inputTemplate;
        entity.type = DefinitionType.AGENT;
        entity.status = AgentStatus.DRAFT;
        entity.createdAt = ZonedDateTime.now();
        entity.updatedAt = entity.createdAt;

        agentDefinitionCollection.insert(entity);
        return toView(entity);
    }

    public AgentDefinitionView enableWebhook(String id) {
        var entity = agentDefinitionCollection.get(id)
                .orElseThrow(() -> new RuntimeException("agent not found, id=" + id));
        entity.webhookSecret = "whk_" + UUID.randomUUID().toString().replace("-", "");
        entity.updatedAt = ZonedDateTime.now();
        agentDefinitionCollection.replace(entity);
        return toView(entity);
    }

    public AgentDefinitionView disableWebhook(String id) {
        var entity = agentDefinitionCollection.get(id)
                .orElseThrow(() -> new RuntimeException("agent not found, id=" + id));
        entity.webhookSecret = null;
        entity.updatedAt = ZonedDateTime.now();
        agentDefinitionCollection.replace(entity);
        return toView(entity);
    }

    public void delete(String id) {
        agentDefinitionCollection.delete(id);
    }

    AgentDefinitionView toView(AgentDefinition entity) {
        var view = new AgentDefinitionView();
        view.id = entity.id;
        view.name = entity.name;
        view.description = entity.description;
        view.systemPrompt = entity.systemPrompt;
        view.model = entity.model;
        view.temperature = entity.temperature;
        view.maxTurns = entity.maxTurns;
        view.timeoutSeconds = entity.timeoutSeconds;
        view.toolIds = entity.toolIds;
        view.inputTemplate = entity.inputTemplate;
        view.variables = entity.variables;
        view.webhookSecret = entity.webhookSecret;
        view.systemDefault = entity.systemDefault;
        view.type = entity.type != null ? entity.type.name() : DefinitionType.AGENT.name();
        view.responseSchema = entity.responseSchema != null ? deserializeResponseSchema(entity.responseSchema) : null;
        view.status = entity.status != null ? entity.status.name() : null;
        view.publishedAt = entity.publishedAt;
        view.createdAt = entity.createdAt;
        view.updatedAt = entity.updatedAt;
        return view;
    }

    private String serializeResponseSchema(List<ApiDefinitionType> schema) {
        return JsonUtil.toJson(schema);
    }

    private List<ApiDefinitionType> deserializeResponseSchema(String json) {
        return JsonUtil.fromJson(new TypeReference<>() { }, json);
    }
}
