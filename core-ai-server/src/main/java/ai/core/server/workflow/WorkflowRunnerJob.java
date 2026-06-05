package ai.core.server.workflow;

import ai.core.server.domain.RunStatus;
import ai.core.server.domain.WorkflowRun;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;

import java.time.ZonedDateTime;

/**
 * Scans for claimable workflow runs and hands each to {@link WorkflowRunner} off-thread. One filter covers both
 * paths: a freshly created PENDING run (lease_until seeded = now) and a crashed RUNNING run whose lease expired.
 * Actively-driven runs keep their lease renewed by the heartbeat, so they fall outside the filter. The CAS in
 * advance() dedupes across replicas. Mirrors AgentSchedulerJob.
 *
 * @author Xander
 */
public class WorkflowRunnerJob implements Job {
    @Inject
    MongoCollection<WorkflowRun> runCollection;

    @Inject
    WorkflowRunner workflowRunner;

    @Override
    public void execute(JobContext context) {
        var now = ZonedDateTime.now();
        var claimable = runCollection.find(Filters.and(
            Filters.in("status", RunStatus.PENDING, RunStatus.RUNNING),
            Filters.lte("lease_until", now)));
        for (WorkflowRun run : claimable) {
            workflowRunner.submit(run.id);
        }
    }
}
