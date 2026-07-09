package ai.core.server.trace.maintenance;

import ai.core.server.task.TaskRunner;
import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Cron trigger that delegates to {@link TaskRunner} so every run produces
 * a persistent {@code background_tasks} record.
 *
 * <p>Runs every hour; uses today's date in the taskId for at-most-once-per-day
 * deduplication via {@link TaskRunner}'s insert-or-skip mechanism.</p>
 *
 * @author cyril
 */
public class TraceArchivingJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(TraceArchivingJob.class);
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Inject
    TaskRunner taskRunner;

    @Inject
    TraceArchivingTask task;

    @Override
    public void execute(JobContext context) {
        var today = LocalDate.now(UTC);
        String taskId = TraceArchivingTask.TYPE + ":" + today;

        try {
            taskRunner.run(task, taskId);
        } catch (Exception e) {
            LOGGER.error("failed to run archive task, taskId={}", taskId, e);
        }
    }
}
