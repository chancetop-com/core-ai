package ai.core.server.agent;

import ai.core.api.server.agent.AgentDefinitionView;
import ai.core.api.server.agent.CreateAgentFromSessionRequest;
import ai.core.api.server.agent.CreateAgentRequest;
import ai.core.api.server.agent.ListAgentsResponse;
import ai.core.api.server.agent.UpdateAgentRequest;
import ai.core.api.server.tool.ToolRefView;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.User;
import ai.core.server.session.AgentSessionManager;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.util.Strings;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * @author stephen
 */
public class AgentDefinitionService {
    private static final String AIRAGENT_USER_ID_FIELD = "user_id";
    private static final String AIRAGENT_SYSTEM_DEFAULT_FIELD = "system_default";

    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;

    @Inject
    MongoCollection<User> userCollection;

    @Inject
    AgentSessionManager sessionManager;

    public AgentDefinitionView create(CreateAgentRequest request, String userId) {
        var existing = agentDefinitionCollection.findOne(Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("name", request.name)));
        if (existing.isPresent()) {
            throw new RuntimeException("agent name already exists: " + request.name);
        }
        var entity = new AgentDefinition();
        entity.id = UUID.randomUUID().toString();
        entity.userId = userId;
        entity.name = request.name;
        entity.description = request.description;
        entity.systemPrompt = request.systemPrompt;
        entity.systemPromptId = Strings.isBlank(request.systemPromptId) ? null : request.systemPromptId;
        entity.model = request.model;
        entity.temperature = request.temperature;
        var isLLMCall = "LLM_CALL".equals(request.type);
        if (!isLLMCall) {
            entity.maxTurns = request.maxTurns != null ? request.maxTurns : 20;
            entity.timeoutSeconds = request.timeoutSeconds != null ? request.timeoutSeconds : 600;
        }
        entity.tools = request.tools != null ? toToolRefs(request.tools) : null;
        entity.subAgentIds = request.subAgentIds;
        entity.skillIds = request.skillIds;
        entity.inputTemplate = request.inputTemplate;
        entity.variables = request.variables;
        entity.type = request.type != null ? DefinitionType.valueOf(request.type) : DefinitionType.AGENT;
        entity.responseSchema = request.responseSchema;
        entity.status = AgentStatus.DRAFT;
        entity.createdAt = ZonedDateTime.now();
        entity.updatedAt = entity.createdAt;

        agentDefinitionCollection.insert(entity);
        return toView(entity);
    }

    public ListAgentsResponse list(String userId) {
        return list(userId, null);
    }

    public ListAgentsResponse list(String userId, Boolean myAgents) {
        List<AgentDefinition> entities;
        if (myAgents != null && myAgents) {
            entities = agentDefinitionCollection.find(Filters.or(
                Filters.eq(AIRAGENT_USER_ID_FIELD, userId),
                Filters.eq(AIRAGENT_SYSTEM_DEFAULT_FIELD, true)
            ));
        } else if (myAgents != null) {
            entities = agentDefinitionCollection.find(Filters.and(
                Filters.ne(AIRAGENT_USER_ID_FIELD, userId),
                Filters.ne(AIRAGENT_SYSTEM_DEFAULT_FIELD, true)
            ));
        } else {
            entities = agentDefinitionCollection.find(Filters.empty());
        }
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

    public AgentDefinitionView getByName(String name, String userId) {
        var entity = agentDefinitionCollection.findOne(Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("name", name)))
                .orElseThrow(() -> new RuntimeException("agent not found, name=" + name));
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
        if (request.systemPromptId != null) entity.systemPromptId = request.systemPromptId;
        if (request.model != null) entity.model = request.model;
        if (request.temperature != null) entity.temperature = request.temperature;
        if (request.maxTurns != null) entity.maxTurns = request.maxTurns;
        if (request.timeoutSeconds != null) entity.timeoutSeconds = request.timeoutSeconds;
        if (request.tools != null) entity.tools = toToolRefs(request.tools);
        if (request.inputTemplate != null) entity.inputTemplate = request.inputTemplate;
        if (request.variables != null) entity.variables = request.variables;
        if (request.responseSchema != null) entity.responseSchema = request.responseSchema;
        if (request.type != null) entity.type = DefinitionType.valueOf(request.type);
        if (request.subAgentIds != null) entity.subAgentIds = request.subAgentIds;
        if (request.skillIds != null) entity.skillIds = request.skillIds;
        entity.updatedAt = ZonedDateTime.now();

        agentDefinitionCollection.replace(entity);
        return toView(entity);
    }

    public AgentDefinitionView publish(String id) {
        var entity = agentDefinitionCollection.get(id)
                .orElseThrow(() -> new RuntimeException("agent not found, id=" + id));

        var config = new AgentPublishedConfig();
        config.systemPrompt = entity.systemPrompt;
        config.systemPromptId = entity.systemPromptId;
        config.model = entity.model;
        config.temperature = entity.temperature;
        config.maxTurns = entity.maxTurns;
        config.timeoutSeconds = entity.timeoutSeconds;
        config.tools = entity.tools;
        config.inputTemplate = entity.inputTemplate;
        config.variables = entity.variables;
        config.responseSchema = entity.responseSchema;
        config.subAgentIds = entity.subAgentIds;
        config.skillIds = entity.skillIds;

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
        entity.tools = agent.getToolCalls().stream()
                .map(tc -> ToolRef.fromLegacyToolId(tc.getName()))
                .toList();
        entity.inputTemplate = request.inputTemplate;
        entity.type = DefinitionType.AGENT;
        entity.status = AgentStatus.DRAFT;
        entity.createdAt = ZonedDateTime.now();
        entity.updatedAt = entity.createdAt;

        agentDefinitionCollection.insert(entity);
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
        view.systemPromptId = entity.systemPromptId;
        view.model = entity.model;
        view.temperature = entity.temperature;
        view.maxTurns = entity.maxTurns;
        view.timeoutSeconds = entity.timeoutSeconds;
        view.tools = toToolRefViews(entity.tools);
        view.inputTemplate = entity.inputTemplate;
        view.variables = entity.variables;
        view.systemDefault = entity.systemDefault;
        view.createdBy = resolveUserName(entity.userId);
        view.type = entity.type != null ? entity.type.name() : DefinitionType.AGENT.name();
        view.responseSchema = entity.responseSchema;
        view.subAgentIds = entity.subAgentIds;
        view.skillIds = entity.skillIds;
        view.status = entity.status != null ? entity.status.name() : null;
        view.publishedAt = entity.publishedAt;
        view.createdAt = entity.createdAt;
        view.updatedAt = entity.updatedAt;
        return view;
    }

    private String resolveUserName(String userId) {
        if (userId == null) return null;
        return userCollection.get(userId).map(u -> u.name).orElse(userId);
    }

    private List<ToolRef> toToolRefs(List<ToolRefView> views) {
        if (views == null || views.isEmpty()) {
            return null;
        }
        return views.stream().map(v -> {
            var ref = new ToolRef();
            ref.id = v.id;
            ref.type = v.type != null ? ToolSourceType.valueOf(v.type) : null;
            ref.source = v.source;
            return ref;
        }).toList();
    }

    private List<ToolRefView> toToolRefViews(List<ToolRef> refs) {
        if (refs == null) return null;
        return refs.stream().map(ref -> {
            var view = new ToolRefView();
            view.id = ref.id;
            view.type = ref.type != null ? ref.type.name() : null;
            view.source = ref.source;
            return view;
        }).toList();
    }

}
