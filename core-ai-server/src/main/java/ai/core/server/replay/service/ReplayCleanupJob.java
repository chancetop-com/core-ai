package ai.core.server.replay.service;

import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes abandoned blank (playground) experiments — created on page open but
 * never edited, never run and never annotated. See
 * {@link ReplayService#cleanupAbandonedBlankExperiments()} for the exact criteria.
 *
 * @author stephen
 */
public class ReplayCleanupJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayCleanupJob.class);

    @Inject
    ReplayService replayService;

    @Override
    public void execute(JobContext context) {
        long removed = replayService.cleanupAbandonedBlankExperiments();
        if (removed > 0) {
            LOGGER.info("replay-blank-cleanup removed {} abandoned blank experiment(s)", removed);
        }
    }
}
