package ai.core.server.web;

import ai.core.api.server.AgentScheduleWebService;
import ai.core.api.server.run.TriggerRunResponse;
import ai.core.api.server.schedule.AgentScheduleView;
import ai.core.api.server.schedule.CreateScheduleRequest;
import ai.core.api.server.schedule.ListSchedulesResponse;
import ai.core.api.server.schedule.ListSessionSchedulesRequest;
import ai.core.api.server.schedule.ListSessionSchedulesResponse;
import ai.core.api.server.schedule.SessionScheduleView;
import ai.core.api.server.schedule.UpdateScheduleRequest;
import ai.core.api.server.schedule.UpdateSessionScheduleRequest;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentSchedule;
import ai.core.server.domain.SessionSchedule;
import ai.core.server.domain.TriggerType;
import ai.core.server.run.AgentRunner;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.schedule.AgentScheduleService;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.WebContext;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class AgentScheduleWebServiceImpl implements AgentScheduleWebService {
    @Inject
    WebContext webContext;
    @Inject
    AgentScheduleService agentScheduleService;
    @Inject
    AgentRunner agentRunner;
    @Inject
    MongoCollection<AgentSchedule> agentScheduleCollection;
    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;
    @Inject
    MongoCollection<SessionSchedule> sessionScheduleCollection;

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_MANAGE)
    public AgentScheduleView create(CreateScheduleRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        return agentScheduleService.create(request, userId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_VIEW)
    public ListSchedulesResponse list() {
        return agentScheduleService.list();
    }

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_VIEW)
    public ListSchedulesResponse listByAgent(String agentId) {
        return agentScheduleService.listByAgent(agentId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_VIEW)
    public ListSessionSchedulesResponse listSessionSchedules(ListSessionSchedulesRequest request) {
        int limit = request.limit == null ? 50 : Math.clamp(request.limit, 1, 200);
        int offset = request.offset == null ? 0 : Math.max(request.offset, 0);
        var query = new Query();
        query.sort = Sorts.descending("created_at");
        query.skip = offset;
        query.limit = limit;
        var response = new ListSessionSchedulesResponse();
        response.sessionSchedules = sessionScheduleCollection.find(query)
                .stream().map(this::toSessionScheduleView).toList();
        response.total = sessionScheduleCollection.count(Filters.empty());
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_MANAGE)
    public SessionScheduleView updateSessionSchedule(String id, UpdateSessionScheduleRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        var entity = sessionScheduleCollection.get(id)
                .orElseThrow(() -> new RuntimeException("session schedule not found, id=" + id));
        entity.enabled = request.enabled;
        entity.updatedAt = ZonedDateTime.now();
        sessionScheduleCollection.replace(entity);
        return toSessionScheduleView(entity);
    }

    private SessionScheduleView toSessionScheduleView(SessionSchedule entity) {
        var view = new SessionScheduleView();
        view.id = entity.id;
        view.sessionId = entity.sessionId;
        view.userId = entity.userId;
        view.name = entity.name;
        view.cronExpression = entity.cronExpression;
        view.timezone = entity.timezone;
        view.input = entity.input;
        view.enabled = entity.enabled;
        view.nextRunAt = entity.nextRunAt;
        view.createdAt = entity.createdAt;
        view.updatedAt = entity.updatedAt;
        return view;
    }

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_MANAGE)
    public AgentScheduleView update(String id, UpdateScheduleRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        return agentScheduleService.update(id, request);
    }

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_MANAGE)
    public void delete(String id) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        agentScheduleService.delete(id);
    }

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_MANAGE)
    public TriggerRunResponse trigger(String id) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);

        var schedule = agentScheduleCollection.get(id)
            .orElseThrow(() -> new RuntimeException("schedule not found, id=" + id));

        var definition = agentDefinitionCollection.get(schedule.agentId)
            .orElseThrow(() -> new RuntimeException("agent not found, agentId=" + schedule.agentId));

        if (definition.publishedConfig == null) {
            throw new RuntimeException("agent not published, agentId=" + schedule.agentId);
        }

        var publishedConfig = definition.publishedConfig;
        var input = schedule.input != null && !schedule.input.isBlank() ? schedule.input : publishedConfig.inputTemplate;

        // manual trigger behaves like the cron fire: deliver output to the configured channel
        var runId = agentRunner.run(definition, input, TriggerType.MANUAL, schedule.id, schedule.variables,
                schedule.channelTarget());

        var response = new TriggerRunResponse();
        response.runId = runId;
        response.status = ai.core.api.server.run.RunStatus.RUNNING;
        return response;
    }
}
