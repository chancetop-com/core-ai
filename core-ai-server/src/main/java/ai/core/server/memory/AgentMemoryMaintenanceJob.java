package ai.core.server.memory;

import ai.core.server.task.TaskRunner;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Cron trigger for deterministic memory governance. Delegates to {@link TaskRunner} so every run
 * produces a persistent {@code background_tasks} record and only one pod executes it.
 */
public class AgentMemoryMaintenanceJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentMemoryMaintenanceJob.class);
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Inject
    TaskRunner taskRunner;

    @Inject
    AgentMemoryMaintenanceTask task;

    @Override
    public void execute(JobContext context) {
        ActionLogContext.maxProcessTime(Duration.ofMinutes(10));
        var taskId = AgentMemoryMaintenanceTask.TYPE + ":" + LocalDate.now(UTC);
        try {
            taskRunner.run(task, taskId);
        } catch (Exception e) {
            LOGGER.error("failed to run agent memory maintenance, taskId={}", taskId, e);
        }
    }
}
