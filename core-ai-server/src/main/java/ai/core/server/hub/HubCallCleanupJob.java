package ai.core.server.hub;

import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class HubCallCleanupJob implements Job {
    @Inject
    HubCallStore hubCallStore;

    @Override
    public void execute(JobContext context) {
        hubCallStore.deleteOlderThan(ZonedDateTime.now().minusDays(90));
    }
}

