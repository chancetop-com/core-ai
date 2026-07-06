package ai.core.server.sandbox.snapshot;

import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author xander
 */
public class SandboxSnapshotCleanupJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxSnapshotCleanupJob.class);

    @Inject
    SandboxSnapshotService snapshotService;

    @Override
    public void execute(JobContext context) {
        var cleaned = snapshotService.cleanupExpired();
        if (cleaned > 0) {
            LOGGER.info("sandbox-snapshot-cleanup removed {} expired snapshot(s)", cleaned);
        }
    }
}
