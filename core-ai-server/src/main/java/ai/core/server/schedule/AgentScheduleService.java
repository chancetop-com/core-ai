package ai.core.server.schedule;

import ai.core.api.server.schedule.AgentScheduleView;
import ai.core.api.server.schedule.CreateScheduleRequest;
import ai.core.api.server.schedule.ListSchedulesResponse;
import ai.core.api.server.schedule.UpdateScheduleRequest;
import ai.core.server.domain.AgentSchedule;
import ai.core.server.domain.ConcurrencyPolicy;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * @author stephen
 */
public class AgentScheduleService {
    @Inject
    MongoCollection<AgentSchedule> agentScheduleCollection;

    public AgentScheduleView create(CreateScheduleRequest request, String userId) {
        var entity = new AgentSchedule();
        entity.id = UUID.randomUUID().toString();
        entity.agentId = request.agentId;
        entity.userId = userId;
        entity.cronExpression = request.cronExpression;
        entity.timezone = request.timezone != null ? request.timezone : "UTC";
        entity.enabled = true;
        entity.input = request.input;
        entity.variables = request.variables;
        entity.concurrencyPolicy = request.concurrencyPolicy != null
            ? ConcurrencyPolicy.valueOf(request.concurrencyPolicy)
            : ConcurrencyPolicy.SKIP;
        entity.createdAt = ZonedDateTime.now();
        entity.updatedAt = entity.createdAt;

        var zone = ZoneId.of(entity.timezone);
        var cron = new CronExpression(entity.cronExpression);
        entity.nextRunAt = cron.nextAfter(ZonedDateTime.now(), zone);

        agentScheduleCollection.insert(entity);
        return toView(entity);
    }

    public ListSchedulesResponse listByAgent(String agentId) {
        var schedules = agentScheduleCollection.find(Filters.eq("agent_id", agentId));
        var response = new ListSchedulesResponse();
        response.schedules = schedules.stream().map(this::toView).toList();
        return response;
    }

    public ListSchedulesResponse list() {
        var schedules = agentScheduleCollection.find(Filters.empty());
        var response = new ListSchedulesResponse();
        response.schedules = schedules.stream().map(this::toView).toList();
        return response;
    }

    public AgentScheduleView update(String id, UpdateScheduleRequest request) {
        var entity = agentScheduleCollection.get(id)
            .orElseThrow(() -> new RuntimeException("schedule not found, id=" + id));

        if (request.cronExpression != null) entity.cronExpression = request.cronExpression;
        if (request.timezone != null) entity.timezone = request.timezone;
        if (request.enabled != null) entity.enabled = request.enabled;
        if (request.input != null) entity.input = request.input;
        if (request.variables != null) entity.variables = request.variables;
        if (request.concurrencyPolicy != null) entity.concurrencyPolicy = ConcurrencyPolicy.valueOf(request.concurrencyPolicy);
        entity.updatedAt = ZonedDateTime.now();

        var zone = ZoneId.of(entity.timezone);
        var cron = new CronExpression(entity.cronExpression);
        entity.nextRunAt = cron.nextAfter(ZonedDateTime.now(), zone);

        agentScheduleCollection.replace(entity);
        return toView(entity);
    }

    public void delete(String id) {
        agentScheduleCollection.delete(id);
    }

    private AgentScheduleView toView(AgentSchedule entity) {
        var view = new AgentScheduleView();
        view.id = entity.id;
        view.agentId = entity.agentId;
        view.cronExpression = entity.cronExpression;
        view.timezone = entity.timezone;
        view.enabled = entity.enabled;
        view.input = entity.input;
        view.variables = entity.variables;
        view.concurrencyPolicy = entity.concurrencyPolicy.name();
        view.nextRunAt = entity.nextRunAt;
        view.createdAt = entity.createdAt;
        view.updatedAt = entity.updatedAt;
        return view;
    }
}
