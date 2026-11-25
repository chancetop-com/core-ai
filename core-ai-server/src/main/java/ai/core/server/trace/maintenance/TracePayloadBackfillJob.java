package ai.core.server.trace.maintenance;

import ai.core.server.task.TaskRunner;
import ai.core.server.trace.service.TracePayloadStore;
import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Schedules a bounded legacy-payload backfill slot. The task id is shared by all pods, so only one
 * pod wins the task insert for a slot.
 *
 * @author core-ai
 */
public class TracePayloadBackfillJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(TracePayloadBackfillJob.class);
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(UTC);
    public static final int TICK_MINUTES = 5;

    @Inject
    TaskRunner taskRunner;
    @Inject
    TracePayloadBackfillTask task;
    @Inject
    TracePayloadStore payloadStore;

    @Override
    public void execute(JobContext context) {
        if (!payloadStore.backfillEnabled()) return;
        var taskId = TracePayloadBackfillTask.TYPE + ":" + SLOT.format(ZonedDateTime.now(UTC));
        try {
            taskRunner.run(task, taskId);
        } catch (Exception e) {
            LOGGER.error("failed to run trace payload backfill, taskId={}", taskId, e);
        }
    }
}