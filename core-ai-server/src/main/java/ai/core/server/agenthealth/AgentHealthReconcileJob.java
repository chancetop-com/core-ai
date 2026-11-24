package ai.core.server.agenthealth;

import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;

/**
 * Safety net for missed deletion/reload hooks and first-deployment backfill.
 *
 * @author core-ai
 */
public class AgentHealthReconcileJob implements Job {
    @Inject
    AgentReferenceTracker referenceTracker;

    @Override
    public void execute(JobContext context) {
        referenceTracker.reconcile();
    }
}
