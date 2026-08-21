package ai.core.server.schedule;

import ai.core.schedule.CronExpression;
import ai.core.schedule.ScheduledTask;
import ai.core.schedule.ScheduledTaskStore;
import ai.core.server.domain.SessionSchedule;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Mongo-backed {@link ScheduledTaskStore} for the server. Mirrors the
 * {@link AgentScheduler} claim pattern: advancing next_run_at is the atomic
 * occurrence claim, so concurrent replicas fire each occurrence exactly once.
 *
 * @author stephen
 */
public class MongoScheduledTaskStore implements ScheduledTaskStore {
    @Inject
    MongoCollection<SessionSchedule> sessionScheduleCollection;

    @Override
    public ScheduledTask create(ScheduledTask task) {
        var entity = toEntity(task);
        var now = ZonedDateTime.now();
        var zone = ZoneId.of(entity.timezone);
        entity.nextRunAt = new CronExpression(entity.cronExpression).nextAfter(now, zone);
        entity.createdAt = now;
        entity.updatedAt = now;
        sessionScheduleCollection.insert(entity);
        return toTask(entity);
    }

    @Override
    public List<ScheduledTask> list(String sessionId) {
        return sessionScheduleCollection.find(Filters.eq("session_id", sessionId)).stream().map(this::toTask).toList();
    }

    @Override
    public ScheduledTask get(String id) {
        return sessionScheduleCollection.get(id).map(this::toTask).orElse(null);
    }

    @Override
    public boolean delete(String id) {
        return sessionScheduleCollection.delete(id);
    }

    @Override
    public List<ScheduledTask> findDue(ZonedDateTime now) {
        return sessionScheduleCollection.find(Filters.and(
                        Filters.eq("enabled", Boolean.TRUE),
                        Filters.lte("next_run_at", now)))
                .stream().map(this::toTask).toList();
    }

    @Override
    public boolean claim(String id, ZonedDateTime expectedNextRunAt, ZonedDateTime newNextRunAt) {
        var updated = sessionScheduleCollection.update(
                Filters.and(
                        Filters.eq("_id", id),
                        Filters.eq("next_run_at", expectedNextRunAt)
                ),
                Updates.set("next_run_at", newNextRunAt)
        );
        return updated == 1;
    }

    private SessionSchedule toEntity(ScheduledTask task) {
        var entity = new SessionSchedule();
        entity.id = task.id;
        entity.sessionId = task.sessionId;
        entity.userId = task.userId;
        entity.name = task.name;
        entity.cronExpression = task.cronExpression;
        entity.timezone = task.timezone;
        entity.input = task.input;
        entity.enabled = task.enabled;
        entity.nextRunAt = task.nextRunAt;
        entity.createdAt = task.createdAt;
        entity.updatedAt = task.updatedAt;
        return entity;
    }

    private ScheduledTask toTask(SessionSchedule entity) {
        var task = new ScheduledTask();
        task.id = entity.id;
        task.sessionId = entity.sessionId;
        task.userId = entity.userId;
        task.name = entity.name;
        task.cronExpression = entity.cronExpression;
        task.timezone = entity.timezone;
        task.input = entity.input;
        task.enabled = entity.enabled;
        task.nextRunAt = entity.nextRunAt;
        task.createdAt = entity.createdAt;
        task.updatedAt = entity.updatedAt;
        return task;
    }
}
