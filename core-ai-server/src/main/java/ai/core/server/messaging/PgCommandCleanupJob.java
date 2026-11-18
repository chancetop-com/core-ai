package ai.core.server.messaging;

import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;

/**
 * Reclaims processing commands whose consumer pod stopped heartbeating and removes old failed rows.
 *
 * @author stephen
 */
public class PgCommandCleanupJob implements Job {
    private final PgCommandQueue queue;

    public PgCommandCleanupJob(PgCommandQueue queue) {
        this.queue = queue;
    }

    @Override
    public void execute(JobContext context) {
        queue.cleanup();
    }
}
