package ai.core.server.home;

import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;

/**
 * @author core-ai
 */
public class HomeTodoDigestJob implements Job {
    @Inject
    HomeTodoDigestService service;

    @Override
    public void execute(JobContext context) {
        service.run();
    }
}
