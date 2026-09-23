package ai.core.server.trace.maintenance;

import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class StaleRunCleanupJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(StaleRunCleanupJob.class);

    @Inject
    StaleRunCleanupService service;

    @Override
    public void execute(JobContext context) {
        try {
            service.cleanup();
        } catch (Exception e) {
            LOGGER.error("stale run cleanup failed", e);
        }
    }
}
