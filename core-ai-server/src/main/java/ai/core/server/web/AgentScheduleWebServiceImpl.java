package ai.core.server.web;

import ai.core.api.server.AgentScheduleWebService;
import ai.core.api.server.run.TriggerRunResponse;
import ai.core.api.server.schedule.AgentScheduleView;
import ai.core.api.server.schedule.CreateScheduleRequest;
import ai.core.api.server.schedule.ListSchedulesResponse;
import ai.core.api.server.schedule.UpdateScheduleRequest;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentSchedule;
import ai.core.server.domain.TriggerType;
import ai.core.server.run.AgentRunner;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.schedule.AgentScheduleService;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.mongo.MongoCollection;
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
    MongoCollection<AgentSchedule> agentScheduleCollection;
    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;

    @Override
    public AgentScheduleView create(CreateScheduleRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        return agentScheduleService.create(request, userId);
    }

    @Override
    public ListSchedulesResponse list() {
        return agentScheduleService.list();
    }

    @Override
    public ListSchedulesResponse listByAgent(String agentId) {
        return agentScheduleService.listByAgent(agentId);
    }

    @Override
    public AgentScheduleView update(String id, UpdateScheduleRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        return agentScheduleService.update(id, request);
    }

    @Override
    public void delete(String id) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        agentScheduleService.delete(id);
    }

    @Override
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

        var runId = agentRunner.run(definition, input, TriggerType.MANUAL, schedule.id, schedule.variables);

        var response = new TriggerRunResponse();
        response.runId = runId;
        response.status = ai.core.api.server.run.RunStatus.RUNNING;
        return response;
    }
}
