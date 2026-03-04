package ai.core.server.schedule;

import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;

/**
 * @author stephen
 */
public class AgentSchedulerJob implements Job {
    @Inject
    AgentScheduler agentScheduler;

    @Override
    public void execute(JobContext context) {
        agentScheduler.evaluate();
    }
}
