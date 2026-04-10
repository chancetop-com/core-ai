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
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.SessionState;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.Session;
import core.framework.web.WebContext;

import java.util.ArrayList;
import java.util.List;

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
            sessionId = createSessionFromAgent(request.agentId, state, userId);
        } else {
            sessionId = sessionManager.createSession(request.config, userId);
            state.fromAgent = false;
        }
        state.sessionId = sessionId;

        var loadedTools = loadToolsOnSessionCreate(sessionId, request);
        var loadedSkills = loadSkillsOnSessionCreate(sessionId, request);

        saveSessionState(sessionId, state);

        var response = new CreateSessionResponse();
        response.sessionId = sessionId;
        response.loadedTools = loadedTools;
        response.loadedSkills = loadedSkills;
        return response;
    }

    private String createSessionFromAgent(String agentId, SessionState state, String userId) {
        var agent = agentDefinitionService.getEntity(agentId);
        var sessionId = sessionManager.createSessionFromAgent(agent, state.config, userId);
        state.fromAgent = true;
        state.agentConfig = buildAgentConfigSnapshot(agent);
        return sessionId;
    }

    private SessionState.AgentConfigSnapshot buildAgentConfigSnapshot(ai.core.server.domain.AgentDefinition agent) {
        var toolRefs = agent.publishedConfig != null ? agent.publishedConfig.tools : agent.tools;
        var snapshot = new SessionState.AgentConfigSnapshot();
        snapshot.systemPrompt = agent.publishedConfig != null && agent.publishedConfig.systemPrompt != null
                ? agent.publishedConfig.systemPrompt : agent.systemPrompt;
        snapshot.model = agent.publishedConfig != null && agent.publishedConfig.model != null
                ? agent.publishedConfig.model : agent.model;
        snapshot.temperature = agent.publishedConfig != null && agent.publishedConfig.temperature != null
                ? agent.publishedConfig.temperature : agent.temperature;
        snapshot.maxTurns = agent.publishedConfig != null && agent.publishedConfig.maxTurns != null
                ? agent.publishedConfig.maxTurns : agent.maxTurns;
        snapshot.tools = toolRefs;
        return snapshot;
    }

    private List<String> loadToolsOnSessionCreate(String sessionId, CreateSessionRequest request) {
        if (request.tools == null || request.tools.isEmpty()) return null;
        var toolRefs = request.tools.stream()
                .map(v -> {
                    var ref = new ToolRef();
                    ref.id = v.id;
                    ref.type = v.type != null ? ToolSourceType.valueOf(v.type) : null;
                    ref.source = v.source;
                    return ref;
                }).toList();
        return sessionManager.loadToolRefs(sessionId, toolRefs);
    }

    private List<String> loadSkillsOnSessionCreate(String sessionId, CreateSessionRequest request) {
        if (request.skillIds == null || request.skillIds.isEmpty()) return null;
        return sessionManager.loadSkills(sessionId, request.skillIds);
    }

    private void saveSessionState(String sessionId, SessionState state) {
        var httpSession = webContext.request().session();
        httpSession.set(SESSION_STATE_KEY + ":" + sessionId, state.toJson());
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
        List<String> loadedTools;
        if (request.tools != null && !request.tools.isEmpty()) {
            var toolRefs = request.tools.stream()
                    .map(v -> {
                        var ref = new ToolRef();
                        ref.id = v.id;
                        ref.type = v.type != null ? ToolSourceType.valueOf(v.type) : null;
                        ref.source = v.source;
                        return ref;
                    }).toList();
            loadedTools = sessionManager.loadToolRefs(sessionId, toolRefs);
        } else {
            loadedTools = List.of();
        }
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
