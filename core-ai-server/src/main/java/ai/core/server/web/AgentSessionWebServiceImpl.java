package ai.core.server.web;

import ai.core.api.server.AgentSessionWebService;
import ai.core.api.server.agent.GenerateAgentDraftResponse;
import ai.core.api.server.session.ApproveToolCallRequest;
import ai.core.api.server.session.CreateSessionRequest;
import ai.core.api.server.session.CreateSessionResponse;
import ai.core.api.server.session.IdName;
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
import ai.core.api.server.session.UnloadSkillsRequest;
import ai.core.api.server.session.UnloadSkillsResponse;
import ai.core.server.agent.AgentDraftGenerator;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.api.server.session.Message;
import ai.core.server.messaging.CommandPublisher;
import ai.core.server.messaging.RpcClient;
import ai.core.server.messaging.SessionCommand;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.server.session.SessionState;
import ai.core.server.skill.SkillService;
import ai.core.server.tool.ToolRegistryService;
import ai.core.tool.ToolCall;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.Session;
import core.framework.web.WebContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    SkillService skillService;
    @Inject
    ChatMessageService chatMessageService;
    @Inject
    CommandPublisher commandPublisher;
    @Inject
    SessionOwnershipRegistry ownershipRegistry;
    @Inject
    RpcClient rpcClient;

    @Override
    public CreateSessionResponse create(CreateSessionRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);

        String sessionId;
        var state = new SessionState();
        state.userId = userId;
        state.config = request.config;
        var loadedSubAgents = new ArrayList<IdName>();
        var loadedSkills = new ArrayList<IdName>();

        if (request.agentId != null && !request.agentId.isBlank()) {
            sessionId = createSessionFromAgent(request.agentId, state, userId, loadedSubAgents, loadedSkills);
        } else {
            sessionId = sessionManager.createSession(request.config, userId);
            state.fromAgent = false;
        }
        state.sessionId = sessionId;

        var loadedTools = loadToolsOnSessionCreate(sessionId, request);
        var extraLoadedSkills = loadSkillsOnSessionCreate(sessionId, request);
        if (extraLoadedSkills != null) {
            for (var skill : extraLoadedSkills) {
                if (loadedSkills.stream().noneMatch(s -> s.id.equals(skill.id))) loadedSkills.add(skill);
            }
        }
        loadExtraSubAgentsOnSessionCreate(sessionId, request, loadedSubAgents);

        saveSessionState(sessionId, state);

        var response = new CreateSessionResponse();
        response.sessionId = sessionId;
        response.loadedTools = loadedTools;
        response.loadedSkills = loadedSkills.isEmpty() ? null : loadedSkills;
        response.loadedSubAgents = loadedSubAgents.isEmpty() ? null : loadedSubAgents;
        return response;
    }

    private String createSessionFromAgent(String agentId, SessionState state, String userId,
                                          List<IdName> loadedSubAgents, List<IdName> loadedSkills) {
        var agent = agentDefinitionService.getEntity(agentId);
        var result = sessionManager.createSessionFromAgent(agent, state.config, userId);
        state.fromAgent = true;
        state.agentConfig = buildAgentConfigSnapshot(agent);
        if (result.loadedSubAgentIds() != null && !result.loadedSubAgentIds().isEmpty()) {
            for (var id : result.loadedSubAgentIds()) {
                String name = resolveAgentName(id);
                var v = new IdName();
                v.id = id;
                v.name = name;
                loadedSubAgents.add(v);
            }
        }
        if (result.loadedSkillIds() != null && !result.loadedSkillIds().isEmpty()) {
            for (var id : result.loadedSkillIds()) {
                String name = resolveSkillName(id);
                var v = new IdName();
                v.id = id;
                v.name = name != null ? name : id;
                loadedSkills.add(v);
            }
        }
        return result.sessionId();
    }

    private SessionState.AgentConfigSnapshot buildAgentConfigSnapshot(ai.core.server.domain.AgentDefinition agent) {
        var published = agent.publishedConfig;
        var toolRefs = published != null ? published.tools : agent.tools;
        var snapshot = new SessionState.AgentConfigSnapshot();
        snapshot.agentName = agent.name;
        snapshot.systemPrompt = published != null && published.systemPrompt != null ? published.systemPrompt : agent.systemPrompt;
        snapshot.systemPromptId = published != null && published.systemPromptId != null ? published.systemPromptId : agent.systemPromptId;
        snapshot.model = published != null && published.model != null ? published.model : agent.model;
        snapshot.temperature = published != null && published.temperature != null ? published.temperature : agent.temperature;
        snapshot.maxTurns = published != null && published.maxTurns != null ? published.maxTurns : agent.maxTurns;
        snapshot.inputTemplate = published != null && published.inputTemplate != null ? published.inputTemplate : agent.inputTemplate;
        snapshot.variables = published != null && published.variables != null ? published.variables : agent.variables;
        snapshot.tools = toolRefs;
        return snapshot;
    }

    private List<IdName> loadToolsOnSessionCreate(String sessionId, CreateSessionRequest request) {
        if (request.tools == null || request.tools.isEmpty()) return null;

        var toolRefs = request.tools.stream()
                .filter(v -> v != null && v.id != null)
                .map(v -> {
                    var ref = new ToolRef();
                    ref.id = v.id;
                    ref.type = v.type != null ? ToolSourceType.valueOf(v.type) : null;
                    ref.source = v.source;
                    if (ref.type == null) ref.inferTypeFromId();
                    return ref;
                }).toList();

        if (toolRefs.isEmpty()) return null;

        var loadedTools = toolRegistryService.resolveToolRefs(toolRefs);
        if (loadedTools.isEmpty()) {
            LOGGER.warn("no tools found for refs, skipping: {}", toolRefs);
            return null;
        }

        var session = sessionManager.getSession(sessionId, resolveSessionState(sessionId));
        session.loadTools(loadedTools);
        chatMessageService.addLoadedTools(sessionId, toolRefs);
        return loadedTools.stream().map(t -> { var v = new IdName(); v.id = t.getName(); v.name = t.getName(); return v; }).toList();
    }

    private List<IdName> loadSkillsOnSessionCreate(String sessionId, CreateSessionRequest request) {
        if (request.skillIds == null || request.skillIds.isEmpty()) return null;
        var names = sessionManager.loadSkills(sessionId, request.skillIds);
        var result = new ArrayList<IdName>(request.skillIds.size());
        for (int i = 0; i < request.skillIds.size() && i < names.size(); i++) {
            var v = new IdName();
            v.id = request.skillIds.get(i);
            v.name = names.get(i);
            result.add(v);
        }
        return result;
    }

    private void loadExtraSubAgentsOnSessionCreate(String sessionId, CreateSessionRequest request, List<IdName> loadedSubAgents) {
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
        for (int i = 0; i < definitions.size() && i < names.size(); i++) {
            var v = new IdName();
            v.id = definitions.get(i).id;
            v.name = names.get(i);
            loadedSubAgents.add(v);
        }
    }

    private String resolveSkillName(String id) {
        try {
            return skillService.get(id).name;
        } catch (Exception e) {
            return id;
        }
    }

    private String resolveAgentName(String id) {
        try {
            return agentDefinitionService.getEntity(id).name;
        } catch (Exception e) {
            return id;
        }
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
        var message = buildMessageWithAttachments(request);
        var command = SessionCommand.sendMessage(sessionId, userId, message,
                request.variables != null ? new HashMap<>(request.variables) : null);
        commandPublisher.publish(command);
    }

    private String buildMessageWithAttachments(SendMessageRequest request) {
        if (request.attachments == null || request.attachments.isEmpty()) {
            return request.message;
        }
        var urls = request.attachments.stream()
                .map(a -> a.url)
                .toList();
        var attachmentText = String.join("\n", urls);
        if (request.message == null || request.message.isBlank()) {
            return attachmentText;
        }
        return request.message + "\n\n" + attachmentText;
    }

    @Override
    public void approve(String sessionId, ApproveToolCallRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);
        var command = SessionCommand.approveToolCall(sessionId, userId, request.callId, request.decision);
        commandPublisher.publish(command);
    }

    @Override
    public SessionHistoryResponse history(String sessionId) {
        var records = chatMessageService.history(sessionId);
        var sessionArtifacts = chatMessageService.artifacts(sessionId);
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
        if (sessionArtifacts != null && !sessionArtifacts.isEmpty()) {
            response.artifacts = sessionArtifacts.stream().map(a -> {
                var v = new ai.core.api.server.session.SessionArtifact();
                v.fileId = a.fileId;
                v.fileName = a.fileName;
                v.contentType = a.contentType;
                v.size = a.size;
                v.title = a.title;
                v.description = a.description;
                return v;
            }).toList();
        }
        return response;
    }

    @Override
    public SessionStatusResponse status(String sessionId) {
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
        var command = SessionCommand.cancelTurn(sessionId, userId);
        commandPublisher.publish(command);
    }

    @Override
    public GenerateAgentDraftResponse generateAgentDraft(String sessionId) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);

        if (isLocalOwner(sessionId)) {
            var session = sessionManager.getSession(sessionId, resolveSessionState(sessionId));
            return agentDraftGenerator.generate(session);
        } else {
            var command = SessionCommand.generateAgentDraft(sessionId, userId, rpcClient.newRequestId());
            return rpcClient.call(command, GenerateAgentDraftResponse.class);
        }
    }

    @Override
    public LoadToolsResponse loadTools(String sessionId, LoadToolsRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);

        if (isLocalOwner(sessionId)) {
            List<IdName> loadedTools;
            if (request.tools != null && !request.tools.isEmpty()) {
                var toolRefs = request.tools.stream()
                        .map(v -> {
                            var ref = new ToolRef();
                            ref.id = v.id;
                            ref.type = v.type != null ? ToolSourceType.valueOf(v.type) : null;
                            ref.source = v.source;
                            if (ref.type == null) ref.inferTypeFromId();
                            return ref;
                        }).toList();
                var names = sessionManager.loadToolRefs(sessionId, toolRefs);
                loadedTools = names.stream().map(n -> { var v = new IdName(); v.id = n; v.name = n; return v; }).toList();
            } else {
                loadedTools = List.of();
            }
            var response = new LoadToolsResponse();
            response.loadedTools = loadedTools;
            return response;
        } else {
            var toolRefs = request.tools != null ? request.tools.stream()
                    .map(v -> {
                        var ref = new ToolRef();
                        ref.id = v.id;
                        ref.type = v.type != null ? ToolSourceType.valueOf(v.type) : null;
                        ref.source = v.source;
                        if (ref.type == null) ref.inferTypeFromId();
                        return ref;
                    }).toList() : null;
            var payload = JsonUtil.toJson(Map.of("tools", toolRefs != null ? toolRefs : List.of()));
            var command = SessionCommand.loadTools(sessionId, userId, payload, rpcClient.newRequestId());
            return rpcClient.call(command, LoadToolsResponse.class);
        }
    }

    @Override
    public LoadSkillsResponse loadSkills(String sessionId, LoadSkillsRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);

        if (isLocalOwner(sessionId)) {
            var names = sessionManager.loadSkills(sessionId, request.skillIds);
            var loadedSkills = new ArrayList<IdName>(request.skillIds.size());
            for (int i = 0; i < request.skillIds.size() && i < names.size(); i++) {
            var v = new IdName();
            v.id = request.skillIds.get(i);
            v.name = names.get(i);
            loadedSkills.add(v);
            }
            var response = new LoadSkillsResponse();
            response.loadedSkills = loadedSkills;
            return response;
        } else {
            var payload = JsonUtil.toJson(Map.of("skillIds", request.skillIds != null ? request.skillIds : List.of()));
            var command = SessionCommand.loadSkills(sessionId, userId, payload, rpcClient.newRequestId());
            return rpcClient.call(command, LoadSkillsResponse.class);
        }
    }

    @Override
    public UnloadSkillsResponse unloadSkills(String sessionId, UnloadSkillsRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);

        if (isLocalOwner(sessionId)) {
            var remainingSkills = sessionManager.unloadSkills(sessionId, request.skillIds);
            var response = new UnloadSkillsResponse();
            response.remainingSkills = remainingSkills;
            return response;
        } else {
            var payload = JsonUtil.toJson(Map.of("skillIds", request.skillIds != null ? request.skillIds : List.of()));
            var command = SessionCommand.unloadSkills(sessionId, userId, payload, rpcClient.newRequestId());
            return rpcClient.call(command, UnloadSkillsResponse.class);
        }
    }

    @Override
    public LoadSubAgentsResponse loadSubAgents(String sessionId, LoadSubAgentsRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);

        if (isLocalOwner(sessionId)) {
            var definitions = request.agentIds.stream()
                    .map(agentDefinitionService::getEntity)
                    .toList();
            var names = sessionManager.loadSubAgents(sessionId, definitions);
            var loadedSubAgents = new ArrayList<IdName>(definitions.size());
            for (int i = 0; i < definitions.size() && i < names.size(); i++) {
                var v = new IdName();
            v.id = definitions.get(i).id;
            v.name = names.get(i);
            loadedSubAgents.add(v);
            }
            var response = new LoadSubAgentsResponse();
            response.loadedSubAgents = loadedSubAgents;
            return response;
        } else {
            var payload = JsonUtil.toJson(Map.of("agentIds", request.agentIds != null ? request.agentIds : List.of()));
            var command = SessionCommand.loadSubAgents(sessionId, userId, payload, rpcClient.newRequestId());
            return rpcClient.call(command, LoadSubAgentsResponse.class);
        }
    }

    @Override
    public void close(String sessionId) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);
        var command = SessionCommand.closeSession(sessionId, userId);
        commandPublisher.publish(command);
    }

    private SessionState resolveSessionState(String sessionId) {
        Session httpSession = webContext.request().session();
        if (httpSession == null) return null;
        var json = httpSession.get(SESSION_STATE_KEY + ":" + sessionId).orElse(null);
        return SessionState.fromJson(json);
    }

    /**
     * Check if this Pod owns the session. If not, the caller should fall through
     * to the RPC path which forwards the request to the owning Pod.
     */
    private boolean isLocalOwner(String sessionId) {
        if (ownershipRegistry == null) return true;  // CLI / no-Redis mode
        return ownershipRegistry.isOwner(sessionId);
    }
}
