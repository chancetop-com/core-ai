package ai.core.server.schedule;

import ai.core.server.tool.ToolRegistryService;
import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;

/**
 * @author stephen
 */
public class ToolRegistrySyncJob implements Job {
    @Inject
    ToolRegistryService toolRegistryService;

    @Override
    public void execute(JobContext context) {
        toolRegistryService.syncDatabaseTools();
    }
}
