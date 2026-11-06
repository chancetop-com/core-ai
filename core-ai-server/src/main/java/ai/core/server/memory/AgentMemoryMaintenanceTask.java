package ai.core.server.memory;

import ai.core.server.task.AbstractTask;
import ai.core.server.task.TaskContext;
import core.framework.inject.Inject;

/**
 * Daily memory housekeeping wrapped as a background task, so multiple server pods run it at most
 * once per day via {@code background_tasks}.
 */
public class AgentMemoryMaintenanceTask extends AbstractTask {
    public static final String TYPE = "AGENT_MEMORY_MAINTENANCE";
    public static final int DEFAULT_TRAJECTORY_RETENTION_DAYS = 90;

    @Inject
    AgentMemoryMaintenanceService maintenanceService;

    public int trajectoryRetentionDays = DEFAULT_TRAJECTORY_RETENTION_DAYS;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void execute(TaskContext ctx) {
        ctx.log("starting agent memory maintenance, trajectoryRetentionDays=" + trajectoryRetentionDays);
        var report = maintenanceService.run(trajectoryRetentionDays);
        ctx.setStatusText("maintained " + report.agents() + " agents, removed "
                + report.removedTrajectories() + " trajectories and "
                + report.removedDuplicates() + " duplicate rows");
        ctx.log("agent memory maintenance completed");
    }
}
