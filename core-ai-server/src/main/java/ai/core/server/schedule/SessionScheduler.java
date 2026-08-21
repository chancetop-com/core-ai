package ai.core.server.schedule;

import ai.core.schedule.CronExpression;
import ai.core.schedule.ScheduledTask;
import ai.core.schedule.ScheduledTaskStore;
import ai.core.server.messaging.CommandPublisher;
import ai.core.server.messaging.SessionCommand;
import ai.core.tool.tools.ScheduledTaskTool;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Fires due session-bound scheduled tasks by publishing a SEND_MESSAGE command to
 * the originating session — the agent continues executing there with the full
 * session context instead of starting a new agent run. Mirrors the
 * {@link AgentScheduler} evaluate/claim pattern for multi-replica safety.
 *
 * @author stephen
 */
public class SessionScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionScheduler.class);

    @Inject
    ScheduledTaskStore scheduledTaskStore;

    @Inject
    CommandPublisher commandPublisher;

    public void evaluate() {
        var now = ZonedDateTime.now();
        var dueTasks = scheduledTaskStore.findDue(now);
        int dueCount = 0;
        for (var task : dueTasks) {
            dueCount++;
            try {
                processTask(task, now);
            } catch (Exception e) {
                LOGGER.error("failed to process scheduled task, id={}, sessionId={}", task.id, task.sessionId, e);
            }
        }
        if (dueCount > 0) {
            LOGGER.info("session scheduler tick, dueCount={}", dueCount);
        } else {
            LOGGER.debug("session scheduler tick, dueCount=0");
        }
    }

    private void processTask(ScheduledTask task, ZonedDateTime now) {
        // Atomic claim: advance next_run_at so other replicas skip this occurrence.
        // Claim happens first so an injection failure consumes the occurrence exactly
        // once instead of re-firing every tick (the failure is logged by the command
        // handler when the session cannot be rebuilt).
        var zone = ZoneId.of(task.timezone);
        var nextRunAt = new CronExpression(task.cronExpression).nextAfter(now, zone);
        if (!scheduledTaskStore.claim(task.id, task.nextRunAt, nextRunAt)) return;

        commandPublisher.publish(SessionCommand.sendMessage(task.sessionId, task.userId,
                ScheduledTaskTool.buildTriggerMessage(task), null));
        LOGGER.info("fired scheduled task, id={}, sessionId={}", task.id, task.sessionId);
    }
}
