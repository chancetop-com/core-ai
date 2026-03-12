package ai.core.server.web;

import ai.core.api.server.AgentRunWebService;
import ai.core.api.server.run.AgentRunDetailView;
import ai.core.api.server.run.LLMCallRequest;
import ai.core.api.server.run.LLMCallResponse;
import ai.core.api.server.run.ListRunsRequest;
import ai.core.api.server.run.ListRunsResponse;
import ai.core.api.server.run.TriggerRunRequest;
import ai.core.api.server.run.TriggerRunResponse;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.run.AgentRunService;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.WebContext;

/**
 * @author stephen
 */
public class AgentRunWebServiceImpl implements AgentRunWebService {
    @Inject
    WebContext webContext;
    @Inject
    AgentRunService agentRunService;

    @Override
    public TriggerRunResponse trigger(String agentId, TriggerRunRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("agent_id", agentId);
        return agentRunService.trigger(agentId, request);
    }

    @Override
    public ListRunsResponse listByAgent(String agentId, ListRunsRequest request) {
        return agentRunService.listByAgent(agentId, request);
    }

    @Override
    public AgentRunDetailView get(String id) {
        return agentRunService.get(id);
    }

    @Override
    public LLMCallResponse llmCall(String id, LLMCallRequest request) {
        ActionLogContext.put("llm_call_id", id);
        return agentRunService.llmCall(id, request);
    }

    @Override
    public void cancel(String id) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("run_id", id);
        agentRunService.cancel(id);
    }
}
