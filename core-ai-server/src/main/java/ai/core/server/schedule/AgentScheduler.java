package ai.core.server.schedule;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.AgentSchedule;
import ai.core.server.domain.ConcurrencyPolicy;
import ai.core.server.domain.RunStatus;
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
import java.util.UUID;

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
    MongoCollection<AgentRun> agentRunCollection;

    @Inject
    AgentRunner agentRunner;

    public void evaluate() {
        var now = ZonedDateTime.now();
        var dueSchedules = agentScheduleCollection.find(
            Filters.and(
                Filters.eq("enabled", Boolean.TRUE),
                Filters.lte("next_run_at", now)
            )
        );

        int dueCount = 0;
        for (var schedule : dueSchedules) {
            dueCount++;
            try {
                processSchedule(schedule, now);
            } catch (Exception e) {
                LOGGER.error("failed to process schedule, id={}", schedule.id, e);
            }
        }
        if (dueCount > 0) {
            LOGGER.info("scheduler tick, dueCount={}", dueCount);
        } else {
            LOGGER.debug("scheduler tick, dueCount=0");
        }
    }

    private void processSchedule(AgentSchedule schedule, ZonedDateTime now) {
        // Atomic claim: advance next_run_at so other replicas skip this occurrence.
        // Claim happens first so SKIP / config problems consume the occurrence exactly once
        // and leave exactly one SKIPPED record, instead of re-firing every tick.
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

        try {
            fireClaimed(schedule);
        } catch (Exception e) {
            // Unexpected failure after claim (Mongo hiccup, runner submit failure): roll back
            // next_run_at to the original due time so the next tick retries, otherwise this
            // occurrence is silently lost until the next cron time.
            rollbackClaim(schedule, nextRunAt);
            throw e;
        }
    }

    private void fireClaimed(AgentSchedule schedule) {
        if (schedule.concurrencyPolicy == ConcurrencyPolicy.SKIP && agentRunner.isRunning(schedule.agentId, schedule.id)) {
            recordSkippedRun(schedule, "previous run of this schedule is still running");
            LOGGER.info("skipping schedule, previous run still running, scheduleId={}, agentId={}", schedule.id, schedule.agentId);
            return;
        }

        var definition = agentDefinitionCollection.get(schedule.agentId);
        if (definition.isEmpty()) {
            recordSkippedRun(schedule, "agent not found, agentId=" + schedule.agentId);
            LOGGER.warn("agent not found for schedule, scheduleId={}, agentId={}", schedule.id, schedule.agentId);
            return;
        }

        if (definition.get().publishedConfig == null) {
            recordSkippedRun(schedule, "agent not published");
            LOGGER.warn("agent not published, skipping schedule, scheduleId={}, agentId={}", schedule.id, schedule.agentId);
            return;
        }

        var publishedConfig = definition.get().publishedConfig;
        var input = schedule.input != null && !schedule.input.isBlank() ? schedule.input : publishedConfig.inputTemplate;
        agentRunner.run(definition.get(), input, TriggerType.SCHEDULE, schedule.id, schedule.variables,
                new AgentRunner.ChannelTarget(schedule.channelId, schedule.channelRecipientId));
        LOGGER.info("triggered scheduled run, scheduleId={}, agentId={}", schedule.id, schedule.agentId);
    }

    private void rollbackClaim(AgentSchedule schedule, ZonedDateTime claimedNextRunAt) {
        try {
            // CAS guarded by the claimed value so a concurrent user edit (which recomputes
            // next_run_at) is never overwritten by the rollback.
            agentScheduleCollection.update(
                Filters.and(
                    Filters.eq("_id", schedule.id),
                    Filters.eq("next_run_at", claimedNextRunAt)
                ),
                Updates.set("next_run_at", schedule.nextRunAt)
            );
            LOGGER.warn("rolled back schedule claim for retry on next tick, scheduleId={}", schedule.id);
        } catch (Exception e) {
            LOGGER.error("failed to roll back schedule claim, occurrence lost, scheduleId={}", schedule.id, e);
        }
    }

    private void recordSkippedRun(AgentSchedule schedule, String reason) {
        var run = new AgentRun();
        run.id = UUID.randomUUID().toString();
        run.agentId = schedule.agentId;
        run.userId = schedule.userId;
        run.triggeredBy = TriggerType.SCHEDULE;
        run.status = RunStatus.SKIPPED;
        run.scheduleId = schedule.id;
        run.input = schedule.input;
        run.error = reason;
        run.startedAt = ZonedDateTime.now();
        run.completedAt = run.startedAt;
        agentRunCollection.insert(run);
    }
}
