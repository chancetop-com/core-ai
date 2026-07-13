package ai.core.server.memory.experiment;

import core.framework.api.http.HTTPStatus;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;

/**
 * Controller for experiment runs listing and config CRUD.
 *
 * @author stephen
 */
public class AgentMemoryExperimentController {
    private static ExperimentRunView toRunView(AgentMemoryExperimentRun r) {
        var v = new ExperimentRunView();
        v.id = r.id;
        v.agentId = r.agentId;
        v.sessionId = r.sessionId;
        v.runId = r.runId;
        v.enabled = r.enabled;
        v.enabledLayers = r.enabledLayers != null
                ? r.enabledLayers.stream().map(MemoryLayerView::from).toList()
                : null;
        v.rankingStrategy = r.rankingStrategy != null
                ? RankingStrategyView.from(r.rankingStrategy)
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

    private static int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Inject
    AgentMemoryExperimentService service;

    public Response listRuns(Request request) {
        var params = request.queryParams();
        var agentId = params.get("agentId");
        int skip = parseIntOrDefault(params.get("skip"), 0);
        int limit = parseIntOrDefault(params.get("limit"), 10);

        var runs = service.findAllRuns(agentId, skip, limit);
        var total = service.countRuns(agentId);

        var views = runs.stream().map(r -> {
            var v = new ExperimentRunView();
            v.id = r.id;
            v.agentId = r.agentId;
            v.sessionId = r.sessionId;
            v.runId = r.runId;
            v.enabled = r.enabled;
            v.enabledLayers = r.enabledLayers != null
                    ? r.enabledLayers.stream().map(MemoryLayerView::from).toList()
                    : null;
            v.rankingStrategy = r.rankingStrategy != null
                    ? RankingStrategyView.from(r.rankingStrategy)
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
        }).toList();

        var resp = new ListExperimentRunsResponse();
        resp.runs = views;
        resp.total = total;
        return Response.bean(resp);
    }

    public Response getRun(Request request) {
        var id = request.pathParam("id");
        var run = service.getRunById(id);
        if (run == null) return Response.text("not found: " + id).status(HTTPStatus.NOT_FOUND);
        return Response.bean(toRunView(run));
    }

    public Response listConfigs(Request request) {
        var params = request.queryParams();
        int skip = parseIntOrDefault(params.get("skip"), 0);
        int limit = parseIntOrDefault(params.get("limit"), 10);

        var configs = service.findAllConfigs(skip, limit);
        var total = service.countConfigs();

        var views = configs.stream().map(c -> {
            var v = new ExperimentConfigListItemView();
            v.id = c.id;
            v.agentId = c.agentId;
            v.enabled = c.enabled;
            v.enabledLayers = c.enabledLayers != null
                    ? c.enabledLayers.stream().map(MemoryLayerView::from).toList()
                    : null;
            v.rankingStrategy = c.rankingStrategy != null
                    ? RankingStrategyView.from(c.rankingStrategy)
                    : null;
            v.topK = c.topK;
            v.injectionProbability = c.injectionProbability;
            return v;
        }).toList();

        var resp = new ListExperimentConfigsResponse();
        resp.configs = views;
        resp.total = total;
        return Response.bean(resp);
    }

    public Response deleteConfig(Request request) {
        var agentId = request.pathParam("id");
        service.deleteConfig(agentId);
        return Response.text("deleted");
    }
}
