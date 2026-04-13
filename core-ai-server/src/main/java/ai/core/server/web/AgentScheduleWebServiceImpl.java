package ai.core.server.web;

import ai.core.api.server.AgentScheduleWebService;
import ai.core.api.server.schedule.AgentScheduleView;
import ai.core.api.server.schedule.CreateScheduleRequest;
import ai.core.api.server.schedule.ListSchedulesResponse;
import ai.core.api.server.schedule.UpdateScheduleRequest;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.schedule.AgentScheduleService;
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
}
