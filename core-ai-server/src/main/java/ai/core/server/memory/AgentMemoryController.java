package ai.core.server.memory;

import ai.core.server.memory.experiment.AgentMemoryExperimentConfigView;
import ai.core.server.memory.experiment.AgentMemoryExperimentService;
import ai.core.server.memory.experiment.AgentMemoryExperimentConfig;
import ai.core.server.memory.experiment.MemoryLayerView;
import ai.core.server.memory.experiment.RankingStrategyView;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;

/**
 * @author stephen
 */
public class AgentMemoryController {
    private static AgentMemoryExperimentConfigView toView(AgentMemoryExperimentConfig config) {
        var v = new AgentMemoryExperimentConfigView();
        v.id = config.id;
        v.agentId = config.agentId;
        v.enabled = config.enabled;
        v.injectionProbability = config.injectionProbability;
        v.enabledLayers = config.enabledLayers != null
                ? config.enabledLayers.stream().map(MemoryLayerView::from).toList()
                : null;
        v.topK = config.topK;
        v.rankingStrategy = config.rankingStrategy != null
                ? RankingStrategyView.from(config.rankingStrategy)
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
                ? view.enabledLayers.stream().map(MemoryLayerView::toEntity).toList()
                : null;
        config.topK = view.topK;
        config.rankingStrategy = view.rankingStrategy != null
                ? view.rankingStrategy.toEntity()
                : null;
        return config;
    }

    @Inject
    AgentMemoryService agentMemoryService;

    @Inject
    AgentMemoryExperimentService agentMemoryExperimentService;

    public Response list(Request request) {
        var agentId = request.pathParam("id");
        var memories = agentMemoryService.findByAgentId(agentId);
        var views = memories.stream().map(m -> {
            var v = new AgentMemoryView();
            v.id = m.id;
            v.agentId = m.agentId;
            v.type = m.type;
            v.layer = MemoryLayerView.from(m.layer);
            v.content = m.content;
            v.sourceTraceIds = m.sourceTraceIds;
            v.createdAt = m.createdAt;
            v.updatedAt = m.updatedAt;
            return v;
        }).toList();
        var resp = new ListAgentMemoriesResponse();
        resp.memories = views;
        return Response.bean(resp);
    }

    public Response getExperimentConfig(Request request) {
        var agentId = request.pathParam("id");
        var config = agentMemoryExperimentService.getConfig(agentId);
        if (config == null) config = agentMemoryExperimentService.resolveConfig(agentId);
        return Response.bean(toView(config));
    }

    public Response delete(Request request) {
        var memoryId = request.pathParam("memoryId");
        agentMemoryService.deleteMemory(memoryId);
        return Response.empty();
    }

    public Response deleteAll(Request request) {
        var agentId = request.pathParam("id");
        agentMemoryService.deleteAllByAgentId(agentId);
        return Response.empty();
    }

    public Response saveExperimentConfig(Request request) {
        var agentId = request.pathParam("id");
        var view = request.bean(AgentMemoryExperimentConfigView.class);
        var config = toEntity(view, agentId);
        var saved = agentMemoryExperimentService.saveConfig(config);
        return Response.bean(toView(saved));
    }
}
