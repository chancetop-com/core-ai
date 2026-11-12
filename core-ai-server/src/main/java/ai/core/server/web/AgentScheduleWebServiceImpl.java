package ai.core.server.web;

import ai.core.server.agent.AgentDefinitionStore;

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
import ai.core.server.agent.PersonalAssistantService;
import ai.core.server.domain.TriggerType;
import ai.core.server.run.AgentRunner;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import ai.core.schedule.ScheduledTask;
import ai.core.server.schedule.AgentScheduleService;
import ai.core.server.schedule.AgentScheduleStore;
import ai.core.server.schedule.SessionScheduleStore;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.WebContext;


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
    AgentScheduleStore agentScheduleStore;
    @Inject
    AgentDefinitionStore agentDefinitionStore;
    @Inject
    SessionScheduleStore sessionScheduleStore;
    @Inject
    PersonalAssistantService personalAssistantService;

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
        var response = new ListSessionSchedulesResponse();
        response.sessionSchedules = sessionScheduleStore.listPage(offset, limit)
                .stream().map(this::toSessionScheduleView).toList();
        response.total = sessionScheduleStore.count();
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.TRIGGER_MANAGE)
    public SessionScheduleView updateSessionSchedule(String id, UpdateSessionScheduleRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        var task = sessionScheduleStore.updateEnabled(id, request.enabled);
        if (task == null) throw new RuntimeException("session schedule not found, id=" + id);
        return toSessionScheduleView(task);
    }

    private SessionScheduleView toSessionScheduleView(ScheduledTask entity) {
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

        var schedule = agentScheduleStore.get(id)
            .orElseThrow(() -> new RuntimeException("schedule not found, id=" + id));

        var agentId = personalAssistantService.resolve(schedule.agentId, schedule.userId);
        var definition = agentDefinitionStore.get(agentId)
            .orElseThrow(() -> new RuntimeException("agent not found, agentId=" + agentId));

        if (definition.publishedConfig == null) {
            throw new RuntimeException("agent not published, agentId=" + agentId);
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



