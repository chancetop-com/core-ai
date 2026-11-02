package ai.core.cli.agent;

import ai.core.schedule.CronExpression;
import ai.core.schedule.ScheduledTaskStore;
import ai.core.tool.tools.ScheduledTaskTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fires due session-bound scheduled tasks while the session is open. The trigger message goes through
 * the same queue as user input, so the sender thread renders it exactly like a user turn. Claiming
 * always advances next_run_at, so occurrences missed while the CLI was closed are not caught up.
 */
final class SessionScheduleTicker {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionScheduleTicker.class);

    private final ScheduledTaskStore store;
    private final String sessionId;
    private ScheduledExecutorService ticker;

    SessionScheduleTicker(ScheduledTaskStore store, String sessionId) {
        this.store = store;
        this.sessionId = sessionId;
    }

    void start(BlockingQueue<String> messageQueue) {
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "schedule-ticker");
            thread.setDaemon(true);
            return thread;
        });
        ticker.scheduleAtFixedRate(() -> tick(messageQueue), 30, 30, TimeUnit.SECONDS);
    }

    void stop() {
        if (ticker != null) {
            ticker.shutdownNow();
            ticker = null;
        }
    }

    private void tick(BlockingQueue<String> messageQueue) {
        try {
            var now = ZonedDateTime.now();
            for (var task : store.findDue(now)) {
                if (!task.sessionId.equals(sessionId)) continue;
                var zone = ZoneId.of(task.timezone);
                var nextRunAt = new CronExpression(task.cronExpression).nextAfter(now, zone);
                if (!store.claim(task.id, task.nextRunAt, nextRunAt)) continue;
                messageQueue.offer(ScheduledTaskTool.buildTriggerMessage(task));
                LOGGER.info("fired scheduled task, id={}, sessionId={}", task.id, task.sessionId);
            }
        } catch (Exception e) {
            LOGGER.warn("schedule tick failed", e);
        }
    }
}
