package ai.core.server.schedule;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentSchedule;
import ai.core.server.domain.ConcurrencyPolicy;
import ai.core.server.domain.TriggerType;
import ai.core.server.run.AgentRunner;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class AgentScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentScheduler.class);

    @Inject
    MongoCollection<AgentSchedule> agentScheduleCollection;

    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;

    @Inject
    AgentRunner agentRunner;

    public void evaluate() {
        var now = ZonedDateTime.now();
        var dueSchedules = agentScheduleCollection.find(
            Filters.and(
                Filters.eq("enabled", true),
                Filters.lte("next_run_at", now)
            )
        );

        for (var schedule : dueSchedules) {
            try {
                processSchedule(schedule, now);
            } catch (Exception e) {
                LOGGER.error("failed to process schedule, id={}", schedule.id, e);
            }
        }
    }

    private void processSchedule(AgentSchedule schedule, ZonedDateTime now) {
        // Atomic lock: try to claim this schedule by updating next_run_at
        var zone = schedule.timezone != null ? ZoneId.of(schedule.timezone) : ZoneId.of("UTC");
        var cron = new CronExpression(schedule.cronExpression);
        var nextRunAt = cron.nextAfter(now, zone);

        long updated = agentScheduleCollection.update(
            Filters.and(
                Filters.eq("_id", schedule.id),
                Filters.lte("next_run_at", now)
            ),
            Updates.set("next_run_at", nextRunAt)
        );

        // If updated == 0, another replica already claimed this schedule
        if (updated == 0) return;

        // Check concurrency policy
        if (schedule.concurrencyPolicy == ConcurrencyPolicy.SKIP && agentRunner.isRunning(schedule.agentId)) {
            LOGGER.info("skipping schedule, agent already running, scheduleId={}, agentId={}", schedule.id, schedule.agentId);
            return;
        }

        var definition = agentDefinitionCollection.get(schedule.agentId);
        if (definition.isEmpty()) {
            LOGGER.warn("agent not found for schedule, scheduleId={}, agentId={}", schedule.id, schedule.agentId);
            return;
        }

        if (definition.get().publishedConfig == null) {
            LOGGER.warn("agent not published, skipping schedule, scheduleId={}, agentId={}", schedule.id, schedule.agentId);
            return;
        }

        var publishedConfig = definition.get().publishedConfig;
        var input = schedule.input != null && !schedule.input.isBlank() ? schedule.input : publishedConfig.inputTemplate;
        agentRunner.run(definition.get(), input, TriggerType.SCHEDULE, schedule.variables);
        LOGGER.info("triggered scheduled run, scheduleId={}, agentId={}", schedule.id, schedule.agentId);
    }
}
