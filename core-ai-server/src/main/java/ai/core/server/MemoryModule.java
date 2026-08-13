package ai.core.server;

import ai.core.api.server.memory.AgentMemoryWebService;
import ai.core.server.memory.AgentMemoryConsolidationJob;
import ai.core.server.memory.AgentMemoryService;
import ai.core.server.memory.AgentMemoryWebServiceImpl;
import ai.core.server.memory.experiment.AgentMemoryExperimentService;
import ai.core.server.settings.SystemSettingsService;
import core.framework.module.Module;

import java.time.Duration;

/**
 * @author stephen
 */
public class MemoryModule extends Module {

    @Override
    protected void initialize() {
        bind(AgentMemoryService.class);
        bind(AgentMemoryExperimentService.class);
        api().service(AgentMemoryWebService.class, bind(AgentMemoryWebServiceImpl.class));

        scheduleJob();
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
        schedule().fixedRate("agent-memory-consolidation", memoryConsolidationJob, Duration.ofHours(1));
    }
}
