package ai.core.server.web;

import ai.core.api.server.AgentSessionWebService;
import ai.core.api.server.agent.GenerateAgentDraftResponse;
import ai.core.api.server.session.ApproveToolCallRequest;
import ai.core.api.server.session.CreateSessionRequest;
import ai.core.api.server.session.CreateSessionResponse;
import ai.core.api.server.session.LoadSkillsRequest;
import ai.core.api.server.session.LoadSkillsResponse;
import ai.core.api.server.session.LoadSubAgentsRequest;
import ai.core.api.server.session.LoadSubAgentsResponse;
import ai.core.api.server.session.LoadToolsRequest;
import ai.core.api.server.session.LoadToolsResponse;
import ai.core.api.server.session.SendMessageRequest;
import ai.core.api.server.session.SessionHistoryResponse;
import ai.core.api.server.session.SessionStatusResponse;
import ai.core.api.server.session.SessionStatus;
import ai.core.server.agent.AgentDraftGenerator;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.SessionState;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.Session;
import core.framework.web.WebContext;

import java.util.ArrayList;

/**
 * @author stephen
 */
public class AgentSessionWebServiceImpl implements AgentSessionWebService {
    private static final String SESSION_STATE_KEY = "agent-session-state";

    @Inject
    WebContext webContext;
    @Inject
    AgentSessionManager sessionManager;
    @Inject
    AgentDefinitionService agentDefinitionService;
    @Inject
    AgentDraftGenerator agentDraftGenerator;

    @Override
    public CreateSessionResponse create(CreateSessionRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);

        String sessionId;
        var state = new SessionState();
        state.userId = userId;
        state.config = request.config;

        if (request.agentId != null) {
            var agent = agentDefinitionService.getEntity(request.agentId);
            sessionId = sessionManager.createSessionFromAgent(agent, request.config, userId);
            state.fromAgent = true;
            var toolIds = agent.publishedConfig != null ? agent.publishedConfig.toolIds : agent.toolIds;
            var snapshot = new SessionState.AgentConfigSnapshot();
            snapshot.systemPrompt = agent.publishedConfig != null && agent.publishedConfig.systemPrompt != null
                    ? agent.publishedConfig.systemPrompt : agent.systemPrompt;
            snapshot.model = agent.publishedConfig != null && agent.publishedConfig.model != null
                    ? agent.publishedConfig.model : agent.model;
            snapshot.temperature = agent.publishedConfig != null && agent.publishedConfig.temperature != null
                    ? agent.publishedConfig.temperature : agent.temperature;
            snapshot.maxTurns = agent.publishedConfig != null && agent.publishedConfig.maxTurns != null
                    ? agent.publishedConfig.maxTurns : agent.maxTurns;
            snapshot.toolIds = toolIds;
            state.agentConfig = snapshot;
        } else {
            sessionId = sessionManager.createSession(request.config, userId);
            state.fromAgent = false;
        }
        state.sessionId = sessionId;

        var httpSession = webContext.request().session();
        if (httpSession != null) {
            httpSession.set(SESSION_STATE_KEY + ":" + sessionId, state.toJson());
        }

        var response = new CreateSessionResponse();
        response.sessionId = sessionId;
        return response;
    }

    @Override
    public void sendMessage(String sessionId, SendMessageRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);
        var session = sessionManager.getSession(sessionId, resolveSessionState(sessionId));
        session.sendMessage(request.message);
    }

    @Override
    public void approve(String sessionId, ApproveToolCallRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);
        var session = sessionManager.getSession(sessionId, resolveSessionState(sessionId));
        session.approveToolCall(request.callId, request.decision);
    }

    @Override
    public SessionHistoryResponse history(String sessionId) {
        sessionManager.getSession(sessionId, resolveSessionState(sessionId));
        var response = new SessionHistoryResponse();
        response.messages = new ArrayList<>();
        return response;
    }

    @Override
    public SessionStatusResponse status(String sessionId) {
        sessionManager.getSession(sessionId, resolveSessionState(sessionId));
        var response = new SessionStatusResponse();
        response.sessionId = sessionId;
        response.status = SessionStatus.IDLE;
        return response;
    }

    @Override
    public void cancel(String sessionId) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);
        var session = sessionManager.getSession(sessionId, resolveSessionState(sessionId));
        session.cancelTurn();
    }

    @Override
    public GenerateAgentDraftResponse generateAgentDraft(String sessionId) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);
        var session = sessionManager.getSession(sessionId, resolveSessionState(sessionId));
        return agentDraftGenerator.generate(session);
    }

    @Override
    public LoadToolsResponse loadTools(String sessionId, LoadToolsRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);
        var loadedTools = sessionManager.loadTools(sessionId, request.toolIds);
        var response = new LoadToolsResponse();
        response.loadedTools = loadedTools;
        return response;
    }

    @Override
    public LoadSkillsResponse loadSkills(String sessionId, LoadSkillsRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);
        var loadedSkills = sessionManager.loadSkills(sessionId, request.skillIds);
        var response = new LoadSkillsResponse();
        response.loadedSkills = loadedSkills;
        return response;
    }

    @Override
    public LoadSubAgentsResponse loadSubAgents(String sessionId, LoadSubAgentsRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);
        var definitions = request.agentIds.stream()
                .map(agentDefinitionService::getEntity)
                .toList();
        var loadedSubAgents = sessionManager.loadSubAgents(sessionId, definitions);
        var response = new LoadSubAgentsResponse();
        response.loadedSubAgents = loadedSubAgents;
        return response;
    }

    @Override
    public void close(String sessionId) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);
        sessionManager.closeSession(sessionId);
    }

    private SessionState resolveSessionState(String sessionId) {
        Session httpSession = webContext.request().session();
        if (httpSession == null) return null;
        var json = httpSession.get(SESSION_STATE_KEY + ":" + sessionId).orElse(null);
        return SessionState.fromJson(json);
    }
}
