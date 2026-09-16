package ai.core.server.memory;

import ai.core.api.server.memory.AgentMemoryExperimentConfigView;
import ai.core.api.server.memory.AgentMemoryView;
import ai.core.api.server.memory.AgentMemoryWebService;
import ai.core.api.server.memory.ExperimentConfigListItemView;
import ai.core.api.server.memory.ExperimentRunView;
import ai.core.api.server.memory.ListAgentMemoriesResponse;
import ai.core.api.server.memory.ListExperimentConfigsRequest;
import ai.core.api.server.memory.ListExperimentConfigsResponse;
import ai.core.api.server.memory.ListExperimentRunsRequest;
import ai.core.api.server.memory.ListExperimentRunsResponse;
import ai.core.api.server.memory.MemoryLayerView;
import ai.core.api.server.memory.RankingStrategyView;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.User;
import ai.core.server.memory.experiment.AgentMemoryExperimentConfig;
import ai.core.server.memory.experiment.AgentMemoryExperimentRun;
import ai.core.server.memory.experiment.AgentMemoryExperimentService;
import ai.core.server.memory.experiment.MemoryLayer;
import ai.core.server.memory.experiment.RankingStrategy;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.rbac.RoleRegistry;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;

/**
 * @author stephen
 */
public class AgentMemoryWebServiceImpl implements AgentMemoryWebService {
    private static MemoryLayerView toLayerView(MemoryLayer layer) {
        return layer == null ? MemoryLayerView.KNOWLEDGE : MemoryLayerView.valueOf(layer.name());
    }

    private static MemoryLayer toLayerEntity(MemoryLayerView view) {
        return MemoryLayer.valueOf(view.name());
    }

    private static RankingStrategyView toStrategyView(RankingStrategy strategy) {
        return RankingStrategyView.valueOf(strategy.name());
    }

    private static RankingStrategy toStrategyEntity(RankingStrategyView view) {
        return RankingStrategy.valueOf(view.name());
    }

    private static AgentMemoryExperimentConfigView toView(AgentMemoryExperimentConfig config) {
        var v = new AgentMemoryExperimentConfigView();
        v.id = config.id;
        v.agentId = config.agentId;
        v.enabled = config.enabled;
        v.injectionProbability = config.injectionProbability;
        v.enabledLayers = config.enabledLayers != null
                ? config.enabledLayers.stream().map(AgentMemoryWebServiceImpl::toLayerView).toList()
                : null;
        v.topK = config.topK;
        v.rankingStrategy = config.rankingStrategy != null
                ? toStrategyView(config.rankingStrategy)
                : null;
        return v;
    }

    private static AgentMemoryExperimentConfig toEntity(AgentMemoryExperimentConfigView view, String agentId) {
        var config = new AgentMemoryExperimentConfig();
        config.id = view.id;
        config.agentId = agentId;
        config.enabled = view.enabled;
        config.injectionProbability = view.injectionProbability;
        config.enabledLayers = view.enabledLayers != null
                ? view.enabledLayers.stream().map(AgentMemoryWebServiceImpl::toLayerEntity).toList()
                : null;
        config.topK = view.topK;
        config.rankingStrategy = view.rankingStrategy != null
                ? toStrategyEntity(view.rankingStrategy)
                : null;
        return config;
    }

    private static ExperimentRunView toRunView(AgentMemoryExperimentRun r) {
        var v = new ExperimentRunView();
        v.id = r.id;
        v.agentId = r.agentId;
        v.sessionId = r.sessionId;
        v.runId = r.runId;
        v.enabled = r.enabled;
        v.enabledLayers = r.enabledLayers != null
                ? r.enabledLayers.stream().map(AgentMemoryWebServiceImpl::toLayerView).toList()
                : null;
        v.rankingStrategy = r.rankingStrategy != null
                ? toStrategyView(r.rankingStrategy)
                : null;
        v.topK = r.topK;
        v.injectionProbability = r.injectionProbability;
        v.injectionDecision = r.injectionDecision;
        v.injectedMemoryIds = r.injectedMemoryIds;
        v.injectedMemoryCount = r.injectedMemoryCount;
        v.layerBreakdown = r.layerBreakdown;
        v.promptTokens = r.promptTokens;
        v.outcome = r.outcome;
        v.userRating = r.userRating;
        v.createdAt = r.createdAt;
        v.updatedAt = r.updatedAt;
        return v;
    }

    private static ExperimentConfigListItemView toConfigListItemView(AgentMemoryExperimentConfig c) {
        var v = new ExperimentConfigListItemView();
        v.id = c.id;
        v.agentId = c.agentId;
        v.enabled = c.enabled;
        v.enabledLayers = c.enabledLayers != null
                ? c.enabledLayers.stream().map(AgentMemoryWebServiceImpl::toLayerView).toList()
                : null;
        v.rankingStrategy = c.rankingStrategy != null
                ? toStrategyView(c.rankingStrategy)
                : null;
        v.topK = c.topK;
        v.injectionProbability = c.injectionProbability;
        return v;
    }

    private static int parseIntOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    @Inject
    AgentMemoryService agentMemoryService;

    @Inject
    AgentMemoryExperimentService agentMemoryExperimentService;

    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;

    @Inject
    MongoCollection<User> userCollection;

    @Inject
    WebContext webContext;

    // A personal assistant's memory is private to its owner: before the fork feature every memory bucket
    // belonged to a shared agent, so no ownership check existed.
    private void requireAgentAccess(String agentId) {
        if (agentId == null || agentId.isBlank()) return;
        var definition = agentDefinitionCollection.get(agentId)
            .orElseThrow(() -> new NotFoundException("agent not found: " + agentId));
        if (Boolean.TRUE.equals(definition.systemDefault)) return;
        var userId = AuthContext.userId(webContext);
        if (userId != null && (userId.equals(definition.userId) || isAdmin(userId))) return;
        throw new ForbiddenException("no access to memories of agent: " + agentId);
    }

    private boolean isAdmin(String userId) {
        return userCollection.get(userId).map(user -> RoleRegistry.ROLE_ADMIN.equals(user.role)).orElse(Boolean.FALSE);
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_VIEW)
    public ListAgentMemoriesResponse listMemories(String agentId) {
        requireAgentAccess(agentId);
        var memories = agentMemoryService.findByAgentId(agentId);
        var views = memories.stream().map(m -> {
            var v = new AgentMemoryView();
            v.id = m.id;
            v.agentId = m.agentId;
            v.type = m.type;
            v.layer = toLayerView(m.layer);
            v.content = m.content;
            v.sourceTraceIds = m.sourceTraceIds;
            v.createdAt = m.createdAt;
            v.updatedAt = m.updatedAt;
            return v;
        }).toList();
        var resp = new ListAgentMemoriesResponse();
        resp.memories = views;
        return resp;
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_VIEW)
    public AgentMemoryExperimentConfigView getExperimentConfig(String agentId) {
        requireAgentAccess(agentId);
        var config = agentMemoryExperimentService.getConfig(agentId);
        if (config == null) config = agentMemoryExperimentService.resolveConfig(agentId);
        return toView(config);
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_MANAGE)
    public void deleteMemory(String agentId, String memoryId) {
        requireAgentAccess(agentId);
        agentMemoryService.deleteMemory(memoryId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_MANAGE)
    public void deleteAllMemories(String agentId) {
        requireAgentAccess(agentId);
        agentMemoryService.deleteAllByAgentId(agentId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_MANAGE)
    public AgentMemoryExperimentConfigView saveExperimentConfig(String agentId, AgentMemoryExperimentConfigView request) {
        requireAgentAccess(agentId);
        var config = toEntity(request, agentId);
        var saved = agentMemoryExperimentService.saveConfig(config);
        return toView(saved);
    }

    @Override
    @PermissionsRequired(PermissionCodes.EXPERIMENT_VIEW)
    public ListExperimentRunsResponse listRuns(ListExperimentRunsRequest request) {
        var agentId = request.agentId;
        requireAgentAccess(agentId);
        int skip = parseIntOrDefault(request.skip, 0);
        int limit = parseIntOrDefault(request.limit, 10);
        var runs = agentMemoryExperimentService.findAllRuns(agentId, skip, limit);
        var total = agentMemoryExperimentService.countRuns(agentId);
        var views = runs.stream().map(AgentMemoryWebServiceImpl::toRunView).toList();
        var resp = new ListExperimentRunsResponse();
        resp.runs = views;
        resp.total = total;
        return resp;
    }

    @Override
    @PermissionsRequired(PermissionCodes.EXPERIMENT_VIEW)
    public ExperimentRunView getRun(String id) {
        var run = agentMemoryExperimentService.getRunById(id);
        if (run == null) throw new NotFoundException("not found: " + id);
        requireAgentAccess(run.agentId);
        return toRunView(run);
    }

    @Override
    @PermissionsRequired(PermissionCodes.EXPERIMENT_VIEW)
    public ListExperimentConfigsResponse listConfigs(ListExperimentConfigsRequest request) {
        int skip = parseIntOrDefault(request.skip, 0);
        int limit = parseIntOrDefault(request.limit, 10);
        var configs = agentMemoryExperimentService.findAllConfigs(skip, limit);
        var total = agentMemoryExperimentService.countConfigs();
        var views = configs.stream().map(AgentMemoryWebServiceImpl::toConfigListItemView).toList();
        var resp = new ListExperimentConfigsResponse();
        resp.configs = views;
        resp.total = total;
        return resp;
    }

    @Override
    @PermissionsRequired(PermissionCodes.EXPERIMENT_VIEW)
    public void deleteExperimentConfig(String agentId) {
        requireAgentAccess(agentId);
        agentMemoryExperimentService.deleteConfig(agentId);
    }
}
