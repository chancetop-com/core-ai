package ai.core.server.asynctask;

import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;

/**
 * Server clock for long-running tool calls: refreshes every open task so results are ready before
 * anyone asks and the owning session is notified the moment a task finishes.
 *
 * @author stephen
 */
public class AsyncToolTaskPollJob implements Job {
    @Inject
    AsyncToolTaskService service;

    @Override
    public void execute(JobContext context) {
        service.pollOpenTasks();
    }
}
