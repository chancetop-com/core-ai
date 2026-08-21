package ai.core.server.schedule;

import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;

/**
 * @author stephen
 */
public class SessionSchedulerJob implements Job {
    @Inject
    SessionScheduler sessionScheduler;

    @Override
    public void execute(JobContext context) {
        sessionScheduler.evaluate();
    }
}
