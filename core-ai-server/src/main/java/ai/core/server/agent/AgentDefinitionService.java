package ai.core.server.agent;

import ai.core.api.server.agent.AgentDefinitionView;
import ai.core.api.server.agent.CreateAgentRequest;
import ai.core.api.server.agent.ListAgentsResponse;
import ai.core.api.server.agent.UpdateAgentRequest;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentStatus;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.bson.types.ObjectId;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class AgentDefinitionService {
    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;

    public AgentDefinitionView create(CreateAgentRequest request, String userId) {
        var entity = new AgentDefinition();
        entity.id = new ObjectId();
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
        entity.status = AgentStatus.DRAFT;
        entity.createdAt = ZonedDateTime.now();
        entity.updatedAt = entity.createdAt;

        agentDefinitionCollection.insert(entity);
        return toView(entity);
    }

    public ListAgentsResponse list(String userId) {
        var entities = agentDefinitionCollection.find(Filters.eq("user_id", userId));
        var response = new ListAgentsResponse();
        response.agents = entities.stream().map(this::toView).toList();
        response.total = (long) response.agents.size();
        return response;
    }

    public AgentDefinitionView get(String id) {
        var entity = agentDefinitionCollection.get(new ObjectId(id))
                .orElseThrow(() -> new RuntimeException("agent not found, id=" + id));
        return toView(entity);
    }

    public AgentDefinitionView update(String id, UpdateAgentRequest request) {
        var entity = agentDefinitionCollection.get(new ObjectId(id))
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
        entity.updatedAt = ZonedDateTime.now();

        agentDefinitionCollection.replace(entity);
        return toView(entity);
    }

    public AgentDefinitionView publish(String id) {
        var entity = agentDefinitionCollection.get(new ObjectId(id))
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

        entity.publishedConfig = config;
        entity.status = AgentStatus.PUBLISHED;
        entity.publishedAt = ZonedDateTime.now();
        entity.updatedAt = entity.publishedAt;

        agentDefinitionCollection.replace(entity);
        return toView(entity);
    }

    public void delete(String id) {
        agentDefinitionCollection.delete(new ObjectId(id));
    }

    private AgentDefinitionView toView(AgentDefinition entity) {
        var view = new AgentDefinitionView();
        view.id = entity.id.toHexString();
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
        view.status = entity.status != null ? entity.status.name() : null;
        view.publishedAt = entity.publishedAt;
        view.createdAt = entity.createdAt;
        view.updatedAt = entity.updatedAt;
        return view;
    }
}
