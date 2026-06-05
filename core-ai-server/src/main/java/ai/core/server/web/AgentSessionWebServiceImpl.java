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
import ai.core.api.server.session.SessionConfig;
import ai.core.api.server.session.SessionHistoryResponse;
import ai.core.api.server.session.SessionStatusResponse;
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
import ai.core.server.util.IdLists;
import ai.core.server.web.sse.SessionChannelService;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.Session;
import core.framework.web.WebContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    @Inject
    ToolRegistryService toolRegistryService;
    @Inject
    SkillService skillService;
    @Inject
    ChatMessageService chatMessageService;
    @Inject
    SessionChannelService sessionChannelService;
    @Inject
    CommandPublisher commandPublisher;
    @Inject
    SessionOwnershipRegistry ownershipRegistry;
    @Inject
    RpcClient rpcClient;
    @Inject
    SessionCreateHelper createHelper;

    @Override
    public CreateSessionResponse create(CreateSessionRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);

        String sessionId;
        var state = new SessionState();
        state.userId = userId;
        state.config = request.config;
        if (state.config == null) {
            state.config = new SessionConfig();
        }
        if (request.datasetConfigs != null && !request.datasetConfigs.isEmpty()) {
            state.config.datasetConfigs = request.datasetConfigs;
        }
        var loadedSubAgents = new ArrayList<IdName>();
        var loadedSkills = new ArrayList<IdName>();

        if (request.agentId != null && !request.agentId.isBlank()) {
            sessionId = createHelper.createSessionFromAgent(request.agentId, state, userId, loadedSubAgents, loadedSkills);
        } else {
            sessionId = sessionManager.createSession(request.config, userId);
            state.fromAgent = false;
        }
        state.sessionId = sessionId;

        var loadedTools = createHelper.loadToolsOnSessionCreate(sessionId, request, resolveSessionState(sessionId));
        var extraLoadedSkills = createHelper.loadSkillsOnSessionCreate(sessionId, request);
        if (extraLoadedSkills != null) {
            for (var skill : extraLoadedSkills) {
                if (loadedSkills.stream().noneMatch(s -> s.id.equals(skill.id))) loadedSkills.add(skill);
            }
        }
        createHelper.loadExtraSubAgentsOnSessionCreate(sessionId, request, loadedSubAgents);

        createHelper.saveSessionState(sessionId, state);

        var response = new CreateSessionResponse();
        response.sessionId = sessionId;
        response.loadedTools = loadedTools;
        response.loadedSkills = loadedSkills.isEmpty() ? null : loadedSkills;
        response.loadedSubAgents = loadedSubAgents.isEmpty() ? null : loadedSubAgents;
        return response;
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
        response.status = sessionChannelService.status(sessionId);
        var meta = chatMessageService.getSessionMeta(sessionId);
        if (meta != null) {
            response.createdAt = meta.createdAt != null ? meta.createdAt.toInstant() : null;
            response.lastActiveAt = meta.lastMessageAt != null ? meta.lastMessageAt.toInstant() : null;
            response.messageCount = meta.messageCount != null ? (int) Math.min(meta.messageCount, (long) Integer.MAX_VALUE) : null;
        }
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
    @SuppressWarnings("checkstyle:NestedIfDepth")
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
                loadedTools = names.stream().map(n -> {
                    var v = new IdName();
                    v.id = n;
                    v.name = n;
                    return v;
                }).toList();
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

        var cleanSkillIds = IdLists.clean(request.skillIds);
        if (isLocalOwner(sessionId)) {
            var names = sessionManager.loadSkills(sessionId, cleanSkillIds);
            var loadedSkills = new ArrayList<IdName>(cleanSkillIds.size());
            for (int i = 0; i < cleanSkillIds.size() && i < names.size(); i++) {
                var v = new IdName();
                v.id = cleanSkillIds.get(i);
                v.name = names.get(i);
                loadedSkills.add(v);
            }
            var response = new LoadSkillsResponse();
            response.loadedSkills = loadedSkills;
            return response;
        } else {
            var payload = JsonUtil.toJson(Map.of("skillIds", cleanSkillIds));
            var command = SessionCommand.loadSkills(sessionId, userId, payload, rpcClient.newRequestId());
            return rpcClient.call(command, LoadSkillsResponse.class);
        }
    }

    @Override
    public UnloadSkillsResponse unloadSkills(String sessionId, UnloadSkillsRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);

        var cleanSkillIds = IdLists.clean(request.skillIds);
        if (isLocalOwner(sessionId)) {
            var remainingSkills = sessionManager.unloadSkills(sessionId, cleanSkillIds);
            var response = new UnloadSkillsResponse();
            response.remainingSkills = remainingSkills;
            return response;
        } else {
            var payload = JsonUtil.toJson(Map.of("skillIds", cleanSkillIds));
            var command = SessionCommand.unloadSkills(sessionId, userId, payload, rpcClient.newRequestId());
            return rpcClient.call(command, UnloadSkillsResponse.class);
        }
    }

    @Override
    public LoadSubAgentsResponse loadSubAgents(String sessionId, LoadSubAgentsRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);

        var cleanAgentIds = IdLists.clean(request.agentIds);
        if (isLocalOwner(sessionId)) {
            var definitions = cleanAgentIds.stream()
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
            var payload = JsonUtil.toJson(Map.of("agentIds", cleanAgentIds));
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
