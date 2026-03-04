package ai.core.server.schedule;

import ai.core.api.server.schedule.AgentScheduleView;
import ai.core.api.server.AgentScheduleWebService;
import ai.core.api.server.schedule.CreateScheduleRequest;
import ai.core.api.server.schedule.ListSchedulesResponse;
import ai.core.api.server.schedule.UpdateScheduleRequest;
import ai.core.server.auth.AuthContext;
import ai.core.server.domain.AgentSchedule;
import ai.core.server.domain.ConcurrencyPolicy;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;
import org.bson.types.ObjectId;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class AgentScheduleWebServiceImpl implements AgentScheduleWebService {
    @Inject
    MongoCollection<AgentSchedule> agentScheduleCollection;

    @Inject
    WebContext webContext;

    @Override
    public AgentScheduleView create(CreateScheduleRequest request) {
        var entity = new AgentSchedule();
        entity.id = new ObjectId();
        entity.agentId = new ObjectId(request.agentId);
        entity.userId = AuthContext.userId(webContext);
        entity.cronExpression = request.cronExpression;
        entity.timezone = request.timezone != null ? request.timezone : "UTC";
        entity.enabled = true;
        entity.input = request.input;
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

    @Override
    public ListSchedulesResponse listByAgent(String agentId) {
        var schedules = agentScheduleCollection.find(Filters.eq("agent_id", new ObjectId(agentId)));
        var response = new ListSchedulesResponse();
        response.schedules = schedules.stream().map(this::toView).toList();
        return response;
    }

    @Override
    public AgentScheduleView update(String id, UpdateScheduleRequest request) {
        var entity = agentScheduleCollection.get(new ObjectId(id))
            .orElseThrow(() -> new RuntimeException("schedule not found, id=" + id));

        if (request.cronExpression != null) entity.cronExpression = request.cronExpression;
        if (request.timezone != null) entity.timezone = request.timezone;
        if (request.enabled != null) entity.enabled = request.enabled;
        if (request.input != null) entity.input = request.input;
        if (request.concurrencyPolicy != null) entity.concurrencyPolicy = ConcurrencyPolicy.valueOf(request.concurrencyPolicy);
        entity.updatedAt = ZonedDateTime.now();

        var zone = ZoneId.of(entity.timezone);
        var cron = new CronExpression(entity.cronExpression);
        entity.nextRunAt = cron.nextAfter(ZonedDateTime.now(), zone);

        agentScheduleCollection.replace(entity);
        return toView(entity);
    }

    @Override
    public void delete(String id) {
        agentScheduleCollection.delete(new ObjectId(id));
    }

    private AgentScheduleView toView(AgentSchedule entity) {
        var view = new AgentScheduleView();
        view.id = entity.id.toHexString();
        view.agentId = entity.agentId.toHexString();
        view.cronExpression = entity.cronExpression;
        view.timezone = entity.timezone;
        view.enabled = entity.enabled;
        view.input = entity.input;
        view.concurrencyPolicy = entity.concurrencyPolicy.name();
        view.nextRunAt = entity.nextRunAt;
        view.createdAt = entity.createdAt;
        view.updatedAt = entity.updatedAt;
        return view;
    }
}
