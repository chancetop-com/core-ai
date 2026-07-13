package ai.core.server;

import ai.core.server.memory.AgentMemoryConsolidationJob;
import ai.core.server.memory.AgentMemoryController;
import ai.core.server.memory.AgentMemoryService;
import ai.core.server.memory.AgentMemoryView;
import ai.core.server.memory.ListAgentMemoriesResponse;
import ai.core.server.memory.experiment.AgentMemoryExperimentConfigView;
import ai.core.server.memory.experiment.AgentMemoryExperimentController;
import ai.core.server.memory.experiment.AgentMemoryExperimentService;
import ai.core.server.memory.experiment.ExperimentConfigListItemView;
import ai.core.server.memory.experiment.ExperimentRunView;
import ai.core.server.memory.experiment.ListExperimentConfigsResponse;
import ai.core.server.memory.experiment.ListExperimentRunsResponse;
import ai.core.server.settings.SystemSettingsService;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

import java.time.Duration;

/**
 * @author stephen
 */
public class MemoryModule extends Module {

    @Override
    protected void initialize() {
        http().bean(AgentMemoryView.class);
        http().bean(ListAgentMemoriesResponse.class);
        http().bean(AgentMemoryExperimentConfigView.class);
        http().bean(ExperimentRunView.class);
        http().bean(ListExperimentRunsResponse.class);
        http().bean(ExperimentConfigListItemView.class);
        http().bean(ListExperimentConfigsResponse.class);

        bind(AgentMemoryService.class);
        bind(AgentMemoryExperimentService.class);

        scheduleJob();

        registerController();
    }

    private void registerController() {
        var memoryController = bind(AgentMemoryController.class);
        http().route(HTTPMethod.GET, "/api/agents/:id/memories", memoryController::list);
        http().route(HTTPMethod.GET, "/api/agents/:id/memory-experiment-config", memoryController::getExperimentConfig);
        http().route(HTTPMethod.PUT, "/api/agents/:id/memory-experiment-config", memoryController::saveExperimentConfig);

        var experimentController = bind(AgentMemoryExperimentController.class);
        http().route(HTTPMethod.GET, "/api/memory-experiments/runs", experimentController::listRuns);
        http().route(HTTPMethod.GET, "/api/memory-experiments/runs/:id", experimentController::getRun);
        http().route(HTTPMethod.GET, "/api/memory-experiments/configs", experimentController::listConfigs);
        http().route(HTTPMethod.DELETE, "/api/agents/:id/memory-experiment-config", experimentController::deleteConfig);
    }

    private void scheduleJob() {
        var memoryConsolidationJob = bind(AgentMemoryConsolidationJob.class);
        memoryConsolidationJob.extractionModel = property("agent.memory.extraction.model")
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .orElse(AgentMemoryConsolidationJob.DEFAULT_EXTRACTION_MODEL);
        var systemSettingsService = bean(SystemSettingsService.class);
        systemSettingsService.defaultMemoryExtractionModel = memoryConsolidationJob.extractionModel;
        systemSettingsService.defaultLlmModel = property("llm.model")
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .orElse(null);
        systemSettingsService.defaultLlmMultiModalModel = property("llm.model.multimodal")
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .orElse(null);
        schedule().fixedRate("agent-memory-consolidation", memoryConsolidationJob, Duration.ofHours(1));
    }
}
