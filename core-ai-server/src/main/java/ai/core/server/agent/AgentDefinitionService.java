package ai.core.server.agent;

import ai.core.api.server.agent.AgentDefinitionView;
import ai.core.api.server.agent.CreateAgentFromSessionRequest;
import ai.core.api.server.agent.CreateAgentRequest;
import ai.core.api.server.agent.ListAgentsRequest;
import ai.core.api.server.agent.ListAgentsResponse;
import ai.core.api.server.agent.UpdateAgentRequest;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.User;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.skill.SkillService;
import ai.core.server.util.IdLists;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.util.Strings;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author stephen
 */
public class AgentDefinitionService {
    private static final String AIRAGENT_USER_ID_FIELD = "user_id";
    private static final String AIRAGENT_SYSTEM_DEFAULT_FIELD = "system_default";
    private static final String DEFAULT_ASSISTANT_AGENT_ID = "default-assistant";

    public static String resolveOutputDatasetId(AgentDefinition definition) {
        return AgentViewHelper.resolveOutputDatasetId(definition);
    }

    public static List<AgentDatasetConfig> resolveDatasetConfig(AgentDefinition definition) {
        return AgentViewHelper.resolveDatasetConfig(definition);
    }

    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;

    @Inject
    MongoCollection<User> userCollection;
    @Inject
    SkillService skillService;

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
        entity.multiModalModel = Strings.isBlank(request.multiModalModel) ? null : request.multiModalModel;
        entity.temperature = request.temperature;
        var isLLMCall = "LLM_CALL".equals(request.type);
        if (!isLLMCall) {
            entity.maxTurns = request.maxTurns != null ? request.maxTurns : 200;
            entity.timeoutSeconds = request.timeoutSeconds != null ? request.timeoutSeconds : 600;
        }
        entity.tools = request.tools != null ? AgentViewHelper.toToolRefs(request.tools) : null;
        entity.subAgentIds = IdLists.cleanOrNull(request.subAgentIds);
        entity.skillIds = IdLists.cleanOrNull(request.skillIds);
        entity.inputTemplate = request.inputTemplate;
        entity.variables = request.variables;
        entity.type = request.type != null ? DefinitionType.valueOf(request.type) : DefinitionType.AGENT;
        entity.responseSchema = request.responseSchema;
        entity.sandboxConfig = request.sandboxConfig != null ? AgentViewHelper.fromSandboxConfigView(request.sandboxConfig) : null;
        entity.datasetConfig = AgentViewHelper.toDatasetConfigs(request.datasetConfig);
        entity.enableMemory = Boolean.TRUE.equals(request.enableMemory);
        if (Boolean.TRUE.equals(request.systemDefault)) {
            if (!isAdmin(userId)) {
                throw new core.framework.web.exception.ForbiddenException("only admin can create system default agents");
            }
            entity.systemDefault = true;
        }
        entity.status = AgentStatus.DRAFT;
        entity.createdAt = ZonedDateTime.now();
        entity.updatedAt = entity.createdAt;

        agentDefinitionCollection.insert(entity);
        return toView(entity);
    }

    public ListAgentsResponse list(String userId) {
        return list(userId, new ListAgentsRequest());
    }

    public ListAgentsResponse list(String userId, ListAgentsRequest request) {
        var effectiveRequest = request != null ? request : new ListAgentsRequest();
        var accessFilter = AgentQueryHelper.buildAccessFilter(userId, effectiveRequest);
        var searchFilter = AgentQueryHelper.buildSearchFilter(effectiveRequest.query);
        var combinedFilter = AgentQueryHelper.combineFilters(accessFilter, searchFilter);

        boolean paginated = effectiveRequest.page != null || effectiveRequest.limit != null;
        int pageNum = effectiveRequest.page != null && effectiveRequest.page > 0 ? effectiveRequest.page : 1;
        int pageSize = effectiveRequest.limit != null && effectiveRequest.limit > 0 ? Math.min(effectiveRequest.limit, 200) : 20;

        var defaultAssistant = findDefaultAssistant(combinedFilter);
        var paged = defaultAssistant != null
            ? listWithDefaultAssistantFirst(combinedFilter, AgentQueryHelper.sortField(effectiveRequest.sort), paginated, pageNum, pageSize, defaultAssistant)
            : findAgents(combinedFilter, AgentQueryHelper.sortField(effectiveRequest.sort), paginated ? (pageNum - 1) * pageSize : null, paginated ? pageSize : null);

        var userNameMap = resolveUserNames(paged);
        var subAgentNameMap = resolveSubAgentNames(paged);
        var skillNameMap = resolveSkillNames(paged);

        var response = new ListAgentsResponse();
        response.agents = paged.stream().map(e -> toView(e, userNameMap, subAgentNameMap, skillNameMap)).toList();
        response.total = agentDefinitionCollection.count(combinedFilter);
        response.page = paginated ? pageNum : null;
        response.limit = paginated ? pageSize : null;
        return response;
    }

    private AgentDefinition findDefaultAssistant(Bson filter) {
        return agentDefinitionCollection.findOne(AgentQueryHelper.combineFilters(filter, Filters.eq("_id", DEFAULT_ASSISTANT_AGENT_ID))).orElse(null);
    }

    private List<AgentDefinition> listWithDefaultAssistantFirst(Bson filter, String sortField, boolean paginated, int pageNum, int pageSize, AgentDefinition defaultAssistant) {
        if (!paginated) {
            var agents = findAgents(filter, sortField, null, null);
            prioritizeDefaultAssistant(agents);
            return agents;
        }
        if (pageNum > 1) {
            return findAgents(excludeDefaultAssistant(filter), sortField, (pageNum - 1) * pageSize - 1, pageSize);
        }
        var agents = pageSize > 1 ? findAgents(excludeDefaultAssistant(filter), sortField, 0, pageSize - 1) : new ArrayList<AgentDefinition>();
        agents.add(0, defaultAssistant);
        return agents;
    }

    private List<AgentDefinition> findAgents(Bson filter, String sortField, Integer skip, Integer limit) {
        var query = new Query();
        query.filter = filter;
        query.sort = Sorts.descending(sortField);
        if (skip != null) query.skip = skip;
        if (limit != null) query.limit = limit;
        return agentDefinitionCollection.find(query);
    }

    private Bson excludeDefaultAssistant(Bson filter) {
        return AgentQueryHelper.combineFilters(filter, Filters.ne("_id", DEFAULT_ASSISTANT_AGENT_ID));
    }

    void prioritizeDefaultAssistant(List<AgentDefinition> agents) {
        for (int i = 0; i < agents.size(); i++) {
            if (DEFAULT_ASSISTANT_AGENT_ID.equals(agents.get(i).id)) {
                agents.add(0, agents.remove(i));
                return;
            }
        }
    }

    private Map<String, String> resolveUserNames(List<AgentDefinition> entities) {
        var userIds = new HashSet<String>();
        for (var entity : entities) {
            if (entity.userId != null) userIds.add(entity.userId);
        }
        if (userIds.isEmpty()) return Map.of();
        var map = new HashMap<String, String>();
        for (var u : userCollection.find(new org.bson.Document("_id", new org.bson.Document("$in", new ArrayList<>(userIds))))) {
            map.put(u.id, u.name);
        }
        return map;
    }

    private Map<String, String> resolveSubAgentNames(List<AgentDefinition> entities) {
        var agentIds = new HashSet<String>();
        for (var entity : entities) {
            if (entity.subAgentIds != null) agentIds.addAll(entity.subAgentIds);
        }
        if (agentIds.isEmpty()) return Map.of();
        var map = new HashMap<String, String>();
        for (var a : agentDefinitionCollection.find(new org.bson.Document("_id", new org.bson.Document("$in", new ArrayList<>(agentIds))))) {
            map.put(a.id, a.name);
        }
        return map;
    }

    private Map<String, String> resolveSkillNames(List<AgentDefinition> entities) {
        var skillIds = new HashSet<String>();
        for (var entity : entities) {
            if (entity.skillIds != null) skillIds.addAll(entity.skillIds);
        }
        if (skillIds.isEmpty()) return Map.of();
        try {
            return skillService.batchResolve(skillIds);
        } catch (Exception e) {
            return Map.of();
        }
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

    public AgentDefinitionView update(String id, UpdateAgentRequest request, String userId) {
        var entity = agentDefinitionCollection.get(id)
                .orElseThrow(() -> new RuntimeException("agent not found, id=" + id));

        requireAdminForSystemDefault(entity, userId);

        if (request.name != null) entity.name = request.name;
        if (request.description != null) entity.description = request.description;
        if (request.systemPrompt != null) entity.systemPrompt = request.systemPrompt;
        if (request.systemPromptId != null) entity.systemPromptId = request.systemPromptId;
        if (request.model != null) entity.model = request.model;
        if (request.multiModalModel != null) entity.multiModalModel = Strings.isBlank(request.multiModalModel) ? null : request.multiModalModel;
        if (request.temperature != null) entity.temperature = request.temperature;
        if (request.maxTurns != null) entity.maxTurns = request.maxTurns;
        if (request.timeoutSeconds != null) entity.timeoutSeconds = request.timeoutSeconds;
        if (request.tools != null) entity.tools = AgentViewHelper.toToolRefs(request.tools);
        if (request.inputTemplate != null) entity.inputTemplate = request.inputTemplate;
        if (request.variables != null) entity.variables = request.variables;
        if (request.responseSchema != null) entity.responseSchema = request.responseSchema;
        if (request.type != null) entity.type = DefinitionType.valueOf(request.type);
        if (request.subAgentIds != null) entity.subAgentIds = IdLists.cleanOrNull(request.subAgentIds);
        if (request.skillIds != null) entity.skillIds = IdLists.cleanOrNull(request.skillIds);
        if (request.sandboxConfig != null) entity.sandboxConfig = AgentViewHelper.fromSandboxConfigView(request.sandboxConfig);
        if (request.datasetConfig != null) entity.datasetConfig = AgentViewHelper.toDatasetConfigs(request.datasetConfig);
        if (request.enableMemory != null) entity.enableMemory = request.enableMemory;
        if (request.systemDefault != null) {
            if (Boolean.TRUE.equals(request.systemDefault) && !isAdmin(userId)) {
                throw new core.framework.web.exception.ForbiddenException("only admin can set agents as system default");
            }
            entity.systemDefault = request.systemDefault;
        }
        entity.updatedAt = ZonedDateTime.now();

        agentDefinitionCollection.replace(entity);
        return toView(entity);
    }

    public AgentDefinitionView publish(String id, String userId) {
        var entity = agentDefinitionCollection.get(id)
                .orElseThrow(() -> new RuntimeException("agent not found, id=" + id));

        requireAdminForSystemDefault(entity, userId);

        entity.subAgentIds = IdLists.cleanOrNull(entity.subAgentIds);
        entity.skillIds = IdLists.cleanOrNull(entity.skillIds);

        var config = new AgentPublishedConfig();
        config.systemPrompt = entity.systemPrompt;
        config.systemPromptId = entity.systemPromptId;
        config.model = entity.model;
        config.multiModalModel = entity.multiModalModel;
        config.temperature = entity.temperature;
        config.maxTurns = entity.maxTurns;
        config.timeoutSeconds = entity.timeoutSeconds;
        config.tools = entity.tools;
        config.inputTemplate = entity.inputTemplate;
        config.variables = entity.variables;
        config.responseSchema = entity.responseSchema;
        config.subAgentIds = IdLists.cleanOrNull(entity.subAgentIds);
        config.skillIds = IdLists.cleanOrNull(entity.skillIds);
        config.sandboxConfig = entity.sandboxConfig;
        config.datasetConfig = entity.datasetConfig;
        config.enableMemory = entity.enableMemory;

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
        entity.multiModalModel = agent.getMultiModalModel();
        entity.temperature = agent.getTemperature();
        entity.maxTurns = 200;
        entity.timeoutSeconds = 600;
        entity.tools = agent.getToolCalls().stream()
                .map(tc -> ToolRef.fromLegacyToolId(tc.getName()))
                .toList();
        entity.inputTemplate = request.inputTemplate;
        entity.type = DefinitionType.AGENT;
        entity.enableMemory = false;
        entity.status = AgentStatus.DRAFT;
        entity.createdAt = ZonedDateTime.now();
        entity.updatedAt = entity.createdAt;

        agentDefinitionCollection.insert(entity);
        return toView(entity);
    }

    public void delete(String id, String userId) {
        var entity = agentDefinitionCollection.get(id)
                .orElseThrow(() -> new RuntimeException("agent not found, id=" + id));
        requireAdminForSystemDefault(entity, userId);
        agentDefinitionCollection.delete(id);
    }

    AgentDefinitionView toView(AgentDefinition entity) {
        var view = AgentViewHelper.buildView(entity, Map.of(), Map.of());
        view.createdBy = resolveUserName(entity.userId);
        if (view.subAgents != null) {
            view.subAgents = view.subAgents.stream().map(sa -> {
                sa.name = resolveAgentName(sa.id);
                return sa;
            }).toList();
        }
        if (view.skills != null) {
            view.skills = view.skills.stream().map(s -> {
                s.name = resolveSkillName(s.id);
                return s;
            }).toList();
        }
        return view;
    }

    AgentDefinitionView toView(AgentDefinition entity, Map<String, String> userNameMap, Map<String, String> subAgentNameMap, Map<String, String> skillNameMap) {
        var view = AgentViewHelper.buildView(entity, subAgentNameMap, skillNameMap);
        view.createdBy = userNameMap.getOrDefault(entity.userId, entity.userId);
        return view;
    }

    private String resolveUserName(String userId) {
        if (userId == null) return null;
        return userCollection.get(userId).map(u -> u.name).orElse(userId);
    }

    private String resolveAgentName(String agentId) {
        try {
            return agentDefinitionCollection.get(agentId).map(a -> a.name).orElse(agentId);
        } catch (Exception e) {
            return agentId;
        }
    }

    private String resolveSkillName(String skillId) {
        try {
            return skillService.get(skillId).name;
        } catch (Exception e) {
            return skillId;
        }
    }

    private void requireAdminForSystemDefault(AgentDefinition entity, String userId) {
        if (!Boolean.TRUE.equals(entity.systemDefault)) return;
        if (userId == null || !isAdmin(userId)) {
            throw new core.framework.web.exception.ForbiddenException("only admin can modify built-in agents");
        }
    }

    private boolean isAdmin(String userId) {
        return userCollection.get(userId).map(u -> "admin".equals(u.role)).orElse(false);
    }
}
