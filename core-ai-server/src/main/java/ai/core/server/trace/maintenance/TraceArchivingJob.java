package ai.core.server.trace.maintenance;

import ai.core.server.settings.TraceArchiveSettings;
import ai.core.server.task.TaskRunner;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Cron trigger that delegates to {@link TaskRunner} so every run produces
 * a persistent {@code background_tasks} record.
 *
 * <p>Wakes on every scheduler tick and decides from the last run recorded in Mongo whether
 * an archive run is due. The interval is read per run, so the value set on the system
 * configuration page applies without a restart.</p>
 *
 * @author cyril
 */
public class TraceArchivingJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(TraceArchivingJob.class);
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final DateTimeFormatter DUE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(UTC);
    private static final int DUE_TOLERANCE_SECONDS = 30;
    public static final int TICK_MINUTES = 5;
    public static final int DEFAULT_INTERVAL_MINUTES = 24 * 60;

    /**
     * Task id for the current due slot, or null while the previous run is still within the interval.
     * When a run is due the id is derived from the previous run, so every pod computes the same id
     * and only one wins the insert; a date-only id would block a second run in the same day when
     * the interval is shorter than a day.
     */
    static String dueTaskId(ZonedDateTime lastStartedAt, int intervalMinutes, ZonedDateTime now) {
        if (lastStartedAt == null) return TraceArchivingTask.TYPE + ":" + DUE_FORMAT.format(now);
        var nextDue = lastStartedAt.plusMinutes(intervalMinutes);
        // a check can land a moment before the exact deadline when the interval is a multiple of the tick;
        // without the tolerance the run would randomly slip a whole tick
        if (now.plusSeconds(DUE_TOLERANCE_SECONDS).isBefore(nextDue)) return null;
        return TraceArchivingTask.TYPE + ":" + DUE_FORMAT.format(nextDue);
    }

    @Inject
    TaskRunner taskRunner;

    @Inject
    TraceArchivingTask task;

    @Inject
    TraceArchiveSettings archiveSettings;

    @Override
    public void execute(JobContext context) {
        // archiving moves large trace batches and takes longer than the 10s scheduler default
        ActionLogContext.maxProcessTime(Duration.ofMinutes(5));
        var taskId = dueTaskId(lastRunStartedAt(), archiveSettings.intervalMinutes(), ZonedDateTime.now());
        if (taskId == null) return;

        try {
            taskRunner.run(task, taskId);
        } catch (Exception e) {
            LOGGER.error("failed to run archive task, taskId={}", taskId, e);
        }
    }

    private ZonedDateTime lastRunStartedAt() {
        var tasks = taskRunner.list(TraceArchivingTask.TYPE, 1);
        return tasks.isEmpty() ? null : tasks.get(0).startedAt;
    }
}
