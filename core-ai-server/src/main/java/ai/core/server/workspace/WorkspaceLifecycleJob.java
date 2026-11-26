package ai.core.server.workspace;

import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.UUID;

/**
 * One sweep a minute: sleep runtimes whose workspace has been idle long enough, purge workspaces whose
 * soft-delete retention elapsed, and retry volumes stuck in DELETING. The job lease keeps a single
 * replica running the sweep; the operations themselves are idempotent so a crashed holder only
 * delays the next tick until its TTL expires.
 *
 * @author stephen
 */
public class WorkspaceLifecycleJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceLifecycleJob.class);
    private static final String JOB = "workspace-lifecycle";
    private static final Duration JOB_LEASE_TTL = Duration.ofMinutes(2);

    @Inject
    WorkspaceService workspaces;
    @Inject
    PgWorkspaceJobLeaseStore jobLeases;

    @Override
    public void execute(JobContext context) {
        if (!workspaces.enabled()) return;
        var holder = UUID.randomUUID().toString();
        if (!jobLeases.tryAcquire(JOB, holder, JOB_LEASE_TTL)) return;
        try {
            var result = workspaces.runLifecycleSweep();
            if (result.slept() > 0 || result.purged() > 0 || result.volumesDeleted() > 0) {
                ActionLogContext.stat("workspace.slept_total", result.slept());
                ActionLogContext.stat("workspace.purged_total", result.purged());
                ActionLogContext.stat("workspace.volumes_deleted_total", result.volumesDeleted());
                LOGGER.info("workspace lifecycle sweep, slept={}, purged={}, volumesDeleted={}",
                        result.slept(), result.purged(), result.volumesDeleted());
            }
        } catch (RuntimeException e) {
            LOGGER.warn("workspace lifecycle sweep failed, retry next tick", e);
        } finally {
            jobLeases.release(JOB, holder);
        }
    }
}
