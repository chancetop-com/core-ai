package ai.core.server;

import ai.core.api.server.memory.AgentMemoryWebService;
import ai.core.server.memory.AgentMemoryConsolidationJob;
import ai.core.server.memory.AgentMemoryMaintenanceJob;
import ai.core.server.memory.AgentMemoryMaintenanceService;
import ai.core.server.memory.AgentMemoryMaintenanceTask;
import ai.core.server.memory.AgentMemoryService;
import ai.core.server.memory.AgentMemoryWebServiceImpl;
import ai.core.server.memory.experiment.AgentMemoryExperimentService;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.task.TaskRunner;
import core.framework.module.Module;

import java.time.Duration;

/**
 * @author stephen
 */
public class MemoryModule extends Module {

    @Override
    protected void initialize() {
        bind(AgentMemoryService.class);
        bind(AgentMemoryMaintenanceService.class);
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

        var maintenanceTask = bind(AgentMemoryMaintenanceTask.class);
        maintenanceTask.trajectoryRetentionDays = property("agent.memory.trajectory.retention.days")
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(Integer::parseInt)
                .orElse(AgentMemoryMaintenanceTask.DEFAULT_TRAJECTORY_RETENTION_DAYS);
        var taskRunner = bean(TaskRunner.class);
        onStartup(() -> taskRunner.register(maintenanceTask));
        schedule().fixedRate("agent-memory-maintenance", bind(AgentMemoryMaintenanceJob.class), Duration.ofDays(1));
    }
}
