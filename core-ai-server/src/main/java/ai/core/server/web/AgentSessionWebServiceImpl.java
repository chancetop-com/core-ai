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
import ai.core.api.server.session.Message;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.server.session.SessionState;
import ai.core.server.tool.ToolRegistryService;
import ai.core.tool.ToolCall;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.Session;
import core.framework.web.WebContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class AgentSessionWebServiceImpl implements AgentSessionWebService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentSessionWebServiceImpl.class);
    private static final String SESSION_STATE_KEY = "agent-session-state";

    @Inject
    WebContext webContext;
    @Inject
    AgentSessionManager sessionManager;
    @Inject
    AgentDefinitionService agentDefinitionService;
    @Inject
    AgentDraftGenerator agentDraftGenerator;
    @Inject
    ToolRegistryService toolRegistryService;
    @Inject
    ChatMessageService chatMessageService;

    @Override
    public CreateSessionResponse create(CreateSessionRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);

        String sessionId;
        var state = new SessionState();
        state.userId = userId;
        state.config = request.config;
        var loadedSubAgents = new ArrayList<String>();

        if (request.agentId != null) {
            sessionId = createSessionFromAgent(request.agentId, state, userId, loadedSubAgents);
        } else {
            sessionId = sessionManager.createSession(request.config, userId);
            state.fromAgent = false;
        }
        state.sessionId = sessionId;

        var loadedTools = loadToolsOnSessionCreate(sessionId, request);
        var loadedSkills = loadSkillsOnSessionCreate(sessionId, request);
        loadExtraSubAgentsOnSessionCreate(sessionId, request, loadedSubAgents);

        saveSessionState(sessionId, state);

        var response = new CreateSessionResponse();
        response.sessionId = sessionId;
        response.loadedTools = loadedTools;
        response.loadedSkills = loadedSkills;
        response.loadedSubAgents = loadedSubAgents.isEmpty() ? null : loadedSubAgents;
        return response;
    }

    private String createSessionFromAgent(String agentId, SessionState state, String userId, List<String> loadedSubAgents) {
        var agent = agentDefinitionService.getEntity(agentId);
        var result = sessionManager.createSessionFromAgent(agent, state.config, userId);
        state.fromAgent = true;
        state.agentConfig = buildAgentConfigSnapshot(agent);
        if (result.loadedSubAgents() != null && !result.loadedSubAgents().isEmpty()) {
            loadedSubAgents.addAll(result.loadedSubAgents());
        }
        return result.sessionId();
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
                .filter(v -> v != null && v.id != null)
                .map(v -> {
                    var ref = new ToolRef();
                    ref.id = v.id;
                    ref.type = v.type != null ? ToolSourceType.valueOf(v.type) : null;
                    ref.source = v.source;
                    return ref;
                }).toList();

        if (toolRefs.isEmpty()) return null;

        var loadedTools = toolRegistryService.resolveToolRefs(toolRefs);
        if (loadedTools.isEmpty()) {
            // Tools requested but none found — log warning but don't fail
            LOGGER.warn("no tools found for refs, skipping: {}", toolRefs);
            return null;
        }

        var session = sessionManager.getSession(sessionId, resolveSessionState(sessionId));
        session.loadTools(loadedTools);
        return loadedTools.stream().map(ToolCall::getName).toList();
    }

    private List<String> loadSkillsOnSessionCreate(String sessionId, CreateSessionRequest request) {
        if (request.skillIds == null || request.skillIds.isEmpty()) return null;
        return sessionManager.loadSkills(sessionId, request.skillIds);
    }

    private void loadExtraSubAgentsOnSessionCreate(String sessionId, CreateSessionRequest request, List<String> loadedSubAgents) {
        if (request.subAgentIds == null || request.subAgentIds.isEmpty()) return;
        var definitions = request.subAgentIds.stream()
                .map(id -> {
                    try {
                        return agentDefinitionService.getEntity(id);
                    } catch (Exception e) {
                        LOGGER.warn("extra subagent not found, id={}", id);
                        return null;
                    }
                })
                .filter(def -> def != null)
                .toList();
        if (definitions.isEmpty()) return;
        var names = sessionManager.loadSubAgents(sessionId, definitions);
        loadedSubAgents.addAll(names);
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
        chatMessageService.writeUserMessage(sessionId, request.message);
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
        var records = chatMessageService.history(sessionId);
        var messages = new ArrayList<Message>(records.size());
        for (var record : records) {
            var msg = new Message();
            msg.role = record.role;
            msg.content = record.content;
            msg.thinking = record.thinking;
            msg.seq = record.seq;
            msg.traceId = record.traceId;
            msg.timestamp = record.createdAt != null ? record.createdAt.toInstant() : null;
            if (record.tools != null) {
                msg.tools = record.tools.stream().map(t -> {
                    var r = new Message.ToolCallRecord();
                    r.callId = t.callId;
                    r.name = t.name;
                    r.arguments = t.arguments;
                    r.result = t.result;
                    r.status = t.status;
                    return r;
                }).toList();
            }
            messages.add(msg);
        }
        var response = new SessionHistoryResponse();
        response.messages = messages;
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
