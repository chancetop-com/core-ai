package ai.core.server.agent;

import ai.core.api.server.agent.AgentDatasetConfigView;
import ai.core.api.server.agent.AgentDefinitionView;
import ai.core.api.server.agent.CreateAgentFromSessionRequest;
import ai.core.api.server.agent.CreateAgentRequest;
import ai.core.api.server.agent.ListAgentsRequest;
import ai.core.api.server.agent.ListAgentsResponse;
import ai.core.api.server.agent.SandboxConfigView;
import ai.core.api.server.agent.UpdateAgentRequest;
import ai.core.api.server.session.IdName;
import ai.core.api.server.tool.ToolRefView;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentSandboxConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DatasetPermission;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
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
import java.util.regex.Pattern;

/**
 * @author stephen
 */
public class AgentDefinitionService {
    private static final String AIRAGENT_USER_ID_FIELD = "user_id";
    private static final String AIRAGENT_SYSTEM_DEFAULT_FIELD = "system_default";
    private static final String DEFAULT_ASSISTANT_AGENT_ID = "default-assistant";

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
        entity.tools = request.tools != null ? toToolRefs(request.tools) : null;
        entity.subAgentIds = IdLists.cleanOrNull(request.subAgentIds);
        entity.skillIds = IdLists.cleanOrNull(request.skillIds);
        entity.inputTemplate = request.inputTemplate;
        entity.variables = request.variables;
        entity.type = request.type != null ? DefinitionType.valueOf(request.type) : DefinitionType.AGENT;
        entity.responseSchema = request.responseSchema;
        entity.sandboxConfig = request.sandboxConfig != null ? fromSandboxConfigView(request.sandboxConfig) : null;
        entity.datasetConfig = toDatasetConfigs(request.datasetConfig);
        entity.enableMemory = Boolean.TRUE.equals(request.enableMemory);
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
        var accessFilter = buildAccessFilter(userId, effectiveRequest);
        var searchFilter = buildSearchFilter(effectiveRequest.query);
        var combinedFilter = combineFilters(accessFilter, searchFilter);

        boolean paginated = effectiveRequest.page != null || effectiveRequest.limit != null;
        int pageNum = effectiveRequest.page != null && effectiveRequest.page > 0 ? effectiveRequest.page : 1;
        int pageSize = effectiveRequest.limit != null && effectiveRequest.limit > 0 ? Math.min(effectiveRequest.limit, 200) : 20;

        var defaultAssistant = findDefaultAssistant(combinedFilter);
        var paged = defaultAssistant != null
            ? listWithDefaultAssistantFirst(combinedFilter, sortField(effectiveRequest.sort), paginated, pageNum, pageSize, defaultAssistant)
            : findAgents(combinedFilter, sortField(effectiveRequest.sort), paginated ? (pageNum - 1) * pageSize : null, paginated ? pageSize : null);

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

    private Bson buildAccessFilter(String userId, ListAgentsRequest request) {
        Boolean myAgents = myAgentsFilter(request.myAgents);
        if (myAgents != null && myAgents) {
            if (Boolean.FALSE.equals(request.includeSystemDefault)) {
                return Filters.and(
                    Filters.eq(AIRAGENT_USER_ID_FIELD, userId),
                    Filters.ne(AIRAGENT_SYSTEM_DEFAULT_FIELD, true)
                );
            }
            return Filters.or(
                Filters.eq(AIRAGENT_USER_ID_FIELD, userId),
                Filters.eq(AIRAGENT_SYSTEM_DEFAULT_FIELD, true)
            );
        } else if (myAgents != null) {
            return Filters.and(
                Filters.ne(AIRAGENT_USER_ID_FIELD, userId),
                Filters.ne(AIRAGENT_SYSTEM_DEFAULT_FIELD, true)
            );
        }
        return Filters.empty();
    }

    private Boolean myAgentsFilter(String myAgents) {
        if (myAgents == null) return null;
        return "true".equalsIgnoreCase(myAgents) || "1".equals(myAgents);
    }

    private String sortField(String sort) {
        if ("created_at".equals(sort)) return "created_at";
        return "updated_at";
    }

    private AgentDefinition findDefaultAssistant(Bson filter) {
        return agentDefinitionCollection.findOne(combineFilters(filter, Filters.eq("_id", DEFAULT_ASSISTANT_AGENT_ID))).orElse(null);
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
        return combineFilters(filter, Filters.ne("_id", DEFAULT_ASSISTANT_AGENT_ID));
    }

    void prioritizeDefaultAssistant(List<AgentDefinition> agents) {
        for (int i = 0; i < agents.size(); i++) {
            if (DEFAULT_ASSISTANT_AGENT_ID.equals(agents.get(i).id)) {
                agents.add(0, agents.remove(i));
                return;
            }
        }
    }

    private Bson buildSearchFilter(String query) {
        if (query == null || query.isBlank()) return Filters.empty();
        var pattern = "(?i)" + Pattern.quote(query.trim());
        return Filters.or(
            Filters.regex("name", pattern),
            Filters.regex("description", pattern)
        );
    }

    private Bson combineFilters(Bson first, Bson second) {
        var firstEmpty = isFilterEmpty(first);
        var secondEmpty = isFilterEmpty(second);
        if (firstEmpty && secondEmpty) return Filters.empty();
        if (firstEmpty) return second;
        if (secondEmpty) return first;
        return Filters.and(first, second);
    }

    private boolean isFilterEmpty(Bson filter) {
        return filter == null
            || filter.getClass().getSimpleName().startsWith("Empty")
            || filter.toBsonDocument().isEmpty();
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

    public AgentDefinitionView update(String id, UpdateAgentRequest request) {
        var entity = agentDefinitionCollection.get(id)
                .orElseThrow(() -> new RuntimeException("agent not found, id=" + id));

        if (request.name != null) entity.name = request.name;
        if (request.description != null) entity.description = request.description;
        if (request.systemPrompt != null) entity.systemPrompt = request.systemPrompt;
        if (request.systemPromptId != null) entity.systemPromptId = request.systemPromptId;
        if (request.model != null) entity.model = request.model;
        if (request.multiModalModel != null) entity.multiModalModel = Strings.isBlank(request.multiModalModel) ? null : request.multiModalModel;
        if (request.temperature != null) entity.temperature = request.temperature;
        if (request.maxTurns != null) entity.maxTurns = request.maxTurns;
        if (request.timeoutSeconds != null) entity.timeoutSeconds = request.timeoutSeconds;
        if (request.tools != null) entity.tools = toToolRefs(request.tools);
        if (request.inputTemplate != null) entity.inputTemplate = request.inputTemplate;
        if (request.variables != null) entity.variables = request.variables;
        if (request.responseSchema != null) entity.responseSchema = request.responseSchema;
        if (request.type != null) entity.type = DefinitionType.valueOf(request.type);
        if (request.subAgentIds != null) entity.subAgentIds = IdLists.cleanOrNull(request.subAgentIds);
        if (request.skillIds != null) entity.skillIds = IdLists.cleanOrNull(request.skillIds);
        if (request.sandboxConfig != null) entity.sandboxConfig = fromSandboxConfigView(request.sandboxConfig);
        if (request.datasetConfig != null) entity.datasetConfig = toDatasetConfigs(request.datasetConfig);
        if (request.enableMemory != null) entity.enableMemory = request.enableMemory;
        entity.updatedAt = ZonedDateTime.now();

        agentDefinitionCollection.replace(entity);
        return toView(entity);
    }

    public AgentDefinitionView publish(String id) {
        var entity = agentDefinitionCollection.get(id)
                .orElseThrow(() -> new RuntimeException("agent not found, id=" + id));

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

    public void delete(String id) {
        agentDefinitionCollection.delete(id);
    }

    AgentDefinitionView toView(AgentDefinition entity) {
        var view = buildView(entity, Map.of(), Map.of(), Map.of());
        // fallback for single-document fetch — individual resolve is OK here (not N+1)
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
        var view = buildView(entity, userNameMap, subAgentNameMap, skillNameMap);
        view.createdBy = userNameMap.getOrDefault(entity.userId, entity.userId);
        return view;
    }

    private AgentDefinitionView buildView(AgentDefinition entity, Map<String, String> userNameMap, Map<String, String> subAgentNameMap, Map<String, String> skillNameMap) {
        var view = new AgentDefinitionView();
        view.id = entity.id;
        view.name = entity.name;
        view.description = entity.description;
        view.systemPrompt = entity.systemPrompt;
        view.systemPromptId = entity.systemPromptId;
        view.model = entity.model;
        view.multiModalModel = entity.multiModalModel;
        view.temperature = entity.temperature;
        view.maxTurns = entity.maxTurns;
        view.timeoutSeconds = entity.timeoutSeconds;
        view.tools = toToolRefViews(entity.tools);
        view.inputTemplate = entity.inputTemplate;
        view.variables = entity.variables;
        view.systemDefault = entity.systemDefault;
        view.enableMemory = entity.enableMemory;
        view.type = entity.type != null ? entity.type.name() : DefinitionType.AGENT.name();
        view.responseSchema = entity.responseSchema;
        view.subAgentIds = IdLists.cleanOrNull(entity.subAgentIds);
        view.skillIds = IdLists.cleanOrNull(entity.skillIds);
        view.subAgents = view.subAgentIds != null ? view.subAgentIds.stream()
                .map(id -> {
                    var v = new IdName();
                    v.id = id;
                    v.name = subAgentNameMap.getOrDefault(id, id);
                    return v;
                })
                .toList() : null;
        view.skills = view.skillIds != null ? view.skillIds.stream()
                .map(id -> {
                    var v = new IdName();
                    v.id = id;
                    v.name = skillNameMap.getOrDefault(id, id);
                    return v;
                })
                .toList() : null;
        view.status = entity.status != null ? entity.status.name() : null;
        view.publishedAt = entity.publishedAt;
        view.createdAt = entity.createdAt;
        view.updatedAt = entity.updatedAt;
        view.sandboxConfig = toSandboxConfigView(entity.sandboxConfig);
        view.datasetConfig = toDatasetConfigViews(entity.datasetConfig);
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

    private List<ToolRef> toToolRefs(List<ToolRefView> views) {
        if (views == null || views.isEmpty()) {
            return null;
        }
        return views.stream().map(v -> {
            var ref = new ToolRef();
            ref.id = v.id;
            ref.type = v.type != null ? ToolSourceType.valueOf(v.type) : null;
            ref.source = v.source;
            if (ref.type == null) ref.inferTypeFromId();
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

    private SandboxConfigView toSandboxConfigView(AgentSandboxConfig config) {
        if (config == null) return null;
        var view = new SandboxConfigView();
        view.enabled = config.enabled;
        view.image = config.image;
        view.memoryLimitMb = config.memoryLimitMb;
        view.cpuLimitMillicores = config.cpuLimitMillicores;
        view.timeoutSeconds = config.timeoutSeconds;
        view.networkEnabled = config.networkEnabled;
        view.gitRepoUrl = config.gitRepoUrl;
        view.gitBranch = config.gitBranch;
        view.tmpSizeLimit = config.tmpSizeLimit;
        view.maxAsyncTasks = config.maxAsyncTasks;
        view.envVars = config.environmentVariables;
        return view;
    }

    private AgentSandboxConfig fromSandboxConfigView(SandboxConfigView view) {
        if (view == null) return null;
        var config = new AgentSandboxConfig();
        config.enabled = view.enabled;
        config.image = view.image;
        config.memoryLimitMb = view.memoryLimitMb;
        config.cpuLimitMillicores = view.cpuLimitMillicores;
        config.timeoutSeconds = view.timeoutSeconds;
        config.networkEnabled = view.networkEnabled;
        config.gitRepoUrl = view.gitRepoUrl;
        config.gitBranch = view.gitBranch;
        config.tmpSizeLimit = view.tmpSizeLimit;
        config.maxAsyncTasks = view.maxAsyncTasks;
        config.environmentVariables = view.envVars;
        return config;
    }

    private List<AgentDatasetConfig> toDatasetConfigs(List<AgentDatasetConfigView> views) {
        if (views == null || views.isEmpty()) return null;
        return views.stream().map(v -> {
            var config = new AgentDatasetConfig();
            config.datasetId = v.datasetId;
            config.permission = v.permission != null ? DatasetPermission.valueOf(v.permission) : DatasetPermission.READ;
            config.isOutput = v.isOutput;
            return config;
        }).toList();
    }

    private List<AgentDatasetConfigView> toDatasetConfigViews(List<AgentDatasetConfig> configs) {
        if (configs == null) return null;
        return configs.stream().map(c -> {
            var view = new AgentDatasetConfigView();
            view.datasetId = c.datasetId;
            view.permission = c.permission.name();
            view.isOutput = c.isOutput;
            return view;
        }).toList();
    }

    public static String resolveOutputDatasetId(AgentDefinition definition) {
        var configs = resolveDatasetConfig(definition);
        if (configs == null) return null;
        return configs.stream()
                .filter(c -> c.isOutput != null && c.isOutput)
                .findFirst()
                .map(c -> c.datasetId)
                .orElse(null);
    }

    public static List<AgentDatasetConfig> resolveDatasetConfig(AgentDefinition definition) {
        var config = definition.publishedConfig;
        if (config != null && config.datasetConfig != null && !config.datasetConfig.isEmpty()) {
            return config.datasetConfig;
        }
        if (definition.datasetConfig != null && !definition.datasetConfig.isEmpty()) {
            return definition.datasetConfig;
        }
        return null;
    }
}
