package ai.core.server.web;

import ai.core.api.server.conversation.ConversationAgentListRequest;
import ai.core.api.server.conversation.ConversationAgentListResponse;
import ai.core.api.server.conversation.ConversationCancelRequest;
import ai.core.api.server.conversation.ConversationCancelResponse;
import ai.core.api.server.conversation.ConversationCapabilitiesResponse;
import ai.core.api.server.conversation.ConversationCreateSessionRequest;
import ai.core.api.server.conversation.ConversationCreateSessionResponse;
import ai.core.api.server.conversation.ConversationHistoryRequest;
import ai.core.api.server.conversation.ConversationHistoryResponse;
import ai.core.api.server.conversation.ConversationRenameRequest;
import ai.core.api.server.conversation.ConversationRenameResponse;
import ai.core.api.server.conversation.ConversationSendRequest;
import ai.core.api.server.conversation.ConversationSendResponse;
import ai.core.api.server.conversation.ConversationSessionListRequest;
import ai.core.api.server.conversation.ConversationSessionListResponse;
import ai.core.api.server.conversation.ConversationSessionSummary;
import ai.core.api.server.conversation.ConversationSnapshotResponse;
import ai.core.api.server.conversation.ConversationWebService;
import ai.core.server.agenthub.AgentHubService;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.agent.PersonalAssistantService;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.conversation.ConversationRequestKeys;
import ai.core.server.conversation.ConversationRequestStore;
import ai.core.server.domain.ChatSession;
import ai.core.server.messaging.CommandPublisher;
import ai.core.server.messaging.SessionCommand;
import ai.core.server.messaging.TurnStateRegistry;
import ai.core.server.messaging.TurnStatusResolver;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.session.ChatMessageService;
import ai.core.server.session.SessionRegistry;
import ai.core.server.session.SessionState;
import ai.core.server.settings.DesktopConversationPolicy;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.user.UserStore;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.web.sse.SessionChannelService;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.UnauthorizedException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Direct-conversation facade. It deliberately delegates creation, messaging, cancellation and
 * history to the same services the web chat uses, so there is one session runtime and one store.
 * The additions are transport-level: a stable client listing, an explicit protocol probe and
 * idempotent create/send commands.
 *
 * @author core-ai
 */
@PermissionsRequired(PermissionCodes.CHAT_USE)
public class ConversationWebServiceImpl implements ConversationWebService {
    private static final int PROTOCOL_VERSION = 1;
    private static final int DEFAULT_AGENT_LIMIT = 50;
    private static final int MAX_AGENT_LIMIT = 100;
    private static final int DEFAULT_SESSION_LIMIT = 30;
    private static final int MAX_SESSION_LIMIT = 100;
    private static final int DEFAULT_HISTORY_LIMIT = 50;
    private static final int MAX_HISTORY_LIMIT = 200;
    private static final String ENTRY_SOURCE = "desktop";

    private static Instant toInstant(java.time.ZonedDateTime value) {
        return value != null ? value.toInstant() : null;
    }

    private static int clamp(Integer value, int fallback, int max) {
        if (value == null || value <= 0) return fallback;
        return Math.min(value, max);
    }

    /**
     * Deployment fallback, used when the server UI policy is "inherit": comma-separated agent ids,
     * empty closes the surface, "*" opens the whole visible catalog.
     */
    public String allowedAgents = "";

    @Inject
    WebContext webContext;
    @Inject
    ChatMessageService chatMessageService;
    @Inject
    SessionRegistry sessionRegistry;
    @Inject
    SessionCreateHelper createHelper;
    @Inject
    AgentHubService agentHubService;
    @Inject
    AgentDefinitionService agentDefinitionService;
    @Inject
    PersonalAssistantService personalAssistantService;
    @Inject
    PermissionService permissionService;
    @Inject
    CommandPublisher commandPublisher;
    @Inject
    TurnStateRegistry turnStateRegistry;
    @Inject
    SessionChannelService sessionChannelService;
    @Inject
    ConversationRequestStore requestStore;
    @Inject
    UserStore userStore;
    @Inject
    SystemSettingsService systemSettingsService;

    @Override
    public ConversationCapabilitiesResponse capabilities() {
        var userId = userId();
        var response = new ConversationCapabilitiesResponse();
        response.protocolVersion = PROTOCOL_VERSION;
        response.subject = userId;
        response.subjectType = userStore.get(userId)
                .map(user -> "api".equals(user.userType) ? "api_service" : "human")
                .orElse("unknown");
        response.directConversation = desktopConversationEnabled();
        response.idempotentCommands = Boolean.TRUE;
        response.historyPaging = Boolean.TRUE;
        // P0 deliberately exposes no upload/approval contract until the server owns those guarantees.
        response.attachmentUpload = Boolean.FALSE;
        response.toolApproval = Boolean.FALSE;
        response.eventStream = "sse:PUT /api/sessions/events";
        response.maxAgents = MAX_AGENT_LIMIT;
        response.maxHistoryMessages = MAX_HISTORY_LIMIT;
        return response;
    }

    @Override
    public ConversationAgentListResponse agents(ConversationAgentListRequest request) {
        var userId = userId();
        var query = request != null ? request.query : null;
        var limit = clamp(request != null ? request.limit : null, DEFAULT_AGENT_LIMIT, MAX_AGENT_LIMIT);
        var response = new ConversationAgentListResponse();
        response.agents = agentHubService.search(userId, query, "agent", null, limit).stream()
                .filter(agent -> isAllowed(agent.id))
                .toList();
        return response;
    }

    @Override
    public ConversationSessionListResponse sessions(ConversationSessionListRequest request) {
        var userId = userId();
        var offset = Math.max(request != null && request.offset != null ? request.offset : 0, 0);
        var limit = clamp(request != null ? request.limit : null, DEFAULT_SESSION_LIMIT, MAX_SESSION_LIMIT);
        var sources = List.of("chat", ENTRY_SOURCE);
        var response = new ConversationSessionListResponse();
        response.total = chatMessageService.countSessions(userId, sources, null);
        response.sessions = chatMessageService.listSessions(userId, sources, offset, limit, "last_message_at")
                .stream().map(this::toSummary).toList();
        return response;
    }

    @Override
    public ConversationCreateSessionResponse create(ConversationCreateSessionRequest request) {
        var userId = userId();
        var agentId = personalAssistantService.resolve(request.agentId, userId);
        if (!isAllowed(agentId)) {
            throw new ForbiddenException("direct conversation is not enabled for this agent");
        }
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("agent_id", agentId);

        var key = ConversationRequestKeys.requestKey(userId, "create", agentId, request.clientRequestId);
        var payloadHash = ConversationRequestKeys.payloadHash(userId, "create", agentId, agentId);
        var existing = existingCreate(key, payloadHash);
        if (existing != null) return existing;

        var claimToken = UUID.randomUUID().toString();
        if (requestStore.claim(key, userId, "create", null, payloadHash, claimToken) == ConversationRequestStore.Claim.EXISTS) {
            var raced = existingCreate(key, payloadHash);
            if (raced != null) return raced;
            throw new ConflictException("REQUEST_IN_PROGRESS");
        }

        try {
            permissionService.check(userId, PermissionService.RESOURCE_TYPE_AGENT, agentId);
            var state = new SessionState();
            state.userId = userId;
            state.source = ENTRY_SOURCE;
            state.config = new ai.core.api.server.session.SessionConfig();
            var sessionId = createHelper.createSessionFromAgent(agentId, state, userId,
                    new ArrayList<>(), new ArrayList<>(), null);
            ActionLogContext.put("session_id", sessionId);

            var definition = agentDefinitionService.getEntity(agentId);
            var response = new ConversationCreateSessionResponse();
            response.sessionId = sessionId;
            response.agentId = agentId;
            response.agentName = definition.name;
            response.model = definition.publishedConfig != null ? definition.publishedConfig.model : definition.model;
            response.created = Boolean.TRUE;
            response.requestStatus = ConversationRequestStore.STATUS_READY;
            requestStore.succeed(key, claimToken, JsonUtil.toJson(response));
            return response;
        } catch (RuntimeException | Error e) {
            requestStore.fail(key, claimToken, e.getMessage());
            throw e;
        }
    }

    @Override
    public ConversationSnapshotResponse snapshot(String sessionId) {
        var userId = userId();
        var session = sessionRegistry.requireAccessible(sessionId, userId);
        var response = new ConversationSnapshotResponse();
        response.sessionId = session.id;
        response.agentId = session.agentId;
        response.agentName = resolveAgentName(session.agentId);
        response.title = session.title;
        response.status = TurnStatusResolver.resolve(turnStateRegistry.liveness(sessionId),
                sessionChannelService.status(sessionId)).name().toLowerCase(java.util.Locale.ROOT);
        response.messageCount = session.messageCount;
        response.lastSeq = chatMessageService.latestSeq(sessionId);
        response.createdAt = toInstant(session.createdAt);
        response.lastActiveAt = toInstant(session.lastMessageAt);
        return response;
    }

    @Override
    public ConversationHistoryResponse history(String sessionId, ConversationHistoryRequest request) {
        var userId = userId();
        sessionRegistry.requireAccessible(sessionId, userId);
        var beforeSeq = request != null ? request.beforeSeq : null;
        var limit = clamp(request != null ? request.limit : null, DEFAULT_HISTORY_LIMIT, MAX_HISTORY_LIMIT);
        var rows = chatMessageService.historyPage(sessionId, beforeSeq, limit + 1);
        var hasMore = rows.size() > limit;
        if (hasMore) rows = rows.subList(rows.size() - limit, rows.size());
        var messages = SessionHistoryHelper.toMessages(rows);
        var response = new ConversationHistoryResponse();
        response.messages = messages;
        response.hasMore = hasMore;
        response.oldestSeq = messages.isEmpty() ? null : messages.get(0).seq;
        if (beforeSeq == null) response.artifacts = SessionHistoryHelper.toArtifacts(chatMessageService.artifacts(sessionId));
        return response;
    }

    @Override
    public ConversationSendResponse send(String sessionId, ConversationSendRequest request) {
        var userId = userId();
        sessionRegistry.requireAccessible(sessionId, userId);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("session_id", sessionId);

        var key = ConversationRequestKeys.requestKey(userId, "send", sessionId, request.clientRequestId);
        var payloadHash = ConversationRequestKeys.payloadHash(userId, "send", sessionId, request.message);
        var existing = existingSend(key, payloadHash);
        if (existing != null) return existing;

        if (turnStateRegistry.liveness(sessionId) == TurnStateRegistry.TurnLiveness.RUNNING) {
            throw new ConflictException("SESSION_BUSY");
        }

        var claimToken = UUID.randomUUID().toString();
        if (requestStore.claim(key, userId, "send", sessionId, payloadHash, claimToken) == ConversationRequestStore.Claim.EXISTS) {
            var raced = existingSend(key, payloadHash);
            if (raced != null) return raced;
            throw new ConflictException("REQUEST_IN_PROGRESS");
        }

        try {
            commandPublisher.publish(SessionCommand.sendMessage(sessionId, userId, request.message, null));
            var response = new ConversationSendResponse();
            response.sessionId = sessionId;
            response.clientRequestId = request.clientRequestId;
            response.requestStatus = ConversationRequestStore.STATUS_READY;
            response.duplicate = Boolean.FALSE;
            requestStore.succeed(key, claimToken, JsonUtil.toJson(response));
            return response;
        } catch (RuntimeException | Error e) {
            requestStore.fail(key, claimToken, e.getMessage());
            throw e;
        }
    }

    @Override
    public ConversationCancelResponse cancel(String sessionId, ConversationCancelRequest request) {
        var userId = userId();
        sessionRegistry.requireAccessible(sessionId, userId);
        var liveness = turnStateRegistry.liveness(sessionId);
        var response = new ConversationCancelResponse();
        response.sessionId = sessionId;
        response.cancelled = liveness != TurnStateRegistry.TurnLiveness.NOT_RUNNING;
        if (Boolean.TRUE.equals(response.cancelled)) {
            commandPublisher.publish(SessionCommand.cancelTurn(sessionId, userId));
        }
        return response;
    }

    @Override
    public ConversationRenameResponse rename(String sessionId, ConversationRenameRequest request) {
        var userId = userId();
        sessionRegistry.requireAccessible(sessionId, userId);
        if (!chatMessageService.updateSessionTitle(userId, sessionId, request.title)) {
            throw new BadRequestException("title is invalid");
        }
        var response = new ConversationRenameResponse();
        response.sessionId = sessionId;
        response.title = request.title.trim();
        return response;
    }

    private ConversationCreateSessionResponse existingCreate(String key, String payloadHash) {
        var receipt = requestStore.find(key).orElse(null);
        if (receipt == null) return null;
        if (!payloadHash.equals(receipt.payloadHash())) throw new ConflictException("IDEMPOTENCY_CONFLICT");
        return switch (receipt.status()) {
            case ConversationRequestStore.STATUS_READY -> {
                var response = JsonUtil.fromJson(ConversationCreateSessionResponse.class, receipt.responseJson());
                response.created = Boolean.FALSE;
                yield response;
            }
            case ConversationRequestStore.STATUS_PENDING -> throw new ConflictException("REQUEST_IN_PROGRESS");
            default -> throw new ConflictException("REQUEST_FAILED: " + receipt.errorMessage());
        };
    }

    private ConversationSendResponse existingSend(String key, String payloadHash) {
        var receipt = requestStore.find(key).orElse(null);
        if (receipt == null) return null;
        if (!payloadHash.equals(receipt.payloadHash())) throw new ConflictException("IDEMPOTENCY_CONFLICT");
        return switch (receipt.status()) {
            case ConversationRequestStore.STATUS_READY -> {
                var response = JsonUtil.fromJson(ConversationSendResponse.class, receipt.responseJson());
                response.duplicate = Boolean.TRUE;
                yield response;
            }
            case ConversationRequestStore.STATUS_PENDING -> throw new ConflictException("REQUEST_IN_PROGRESS");
            default -> throw new ConflictException("REQUEST_FAILED: " + receipt.errorMessage());
        };
    }

    private ConversationSessionSummary toSummary(ChatSession session) {
        var summary = new ConversationSessionSummary();
        summary.id = session.id;
        summary.agentId = session.agentId;
        summary.agentName = resolveAgentName(session.agentId);
        summary.title = session.title;
        summary.source = session.source;
        summary.messageCount = session.messageCount;
        summary.createdAt = toInstant(session.createdAt);
        summary.lastMessageAt = toInstant(session.lastMessageAt);
        return summary;
    }

    private String resolveAgentName(String agentId) {
        if (agentId == null) return null;
        try {
            return agentDefinitionService.getEntity(agentId).name;
        } catch (RuntimeException e) {
            return agentId;
        }
    }

    private boolean isAllowed(String agentId) {
        var mode = systemSettingsService.desktopDirectConversationMode();
        if (DesktopConversationPolicy.MODE_OFF.equals(mode)) return false;
        if (DesktopConversationPolicy.MODE_ALL.equals(mode)) return true;
        if (DesktopConversationPolicy.MODE_SELECTED.equals(mode)) {
            return systemSettingsService.desktopDirectConversationAgents().contains(agentId);
        }
        return deploymentAllows(agentId);
    }

    private boolean desktopConversationEnabled() {
        var mode = systemSettingsService.desktopDirectConversationMode();
        if (DesktopConversationPolicy.MODE_OFF.equals(mode)) return false;
        if (DesktopConversationPolicy.MODE_ALL.equals(mode)) return true;
        if (DesktopConversationPolicy.MODE_SELECTED.equals(mode)) {
            return !systemSettingsService.desktopDirectConversationAgents().isEmpty();
        }
        return allowedAgents != null && !allowedAgents.isBlank();
    }

    private boolean deploymentAllows(String agentId) {
        if (allowedAgents == null || allowedAgents.isBlank()) return false;
        var value = allowedAgents.trim();
        if ("*".equals(value)) return true;
        for (var id : value.split(",")) {
            if (id.trim().equals(agentId)) return true;
        }
        return false;
    }

    private String userId() {
        var userId = AuthContext.userId(webContext);
        if (userId == null) throw new UnauthorizedException("unauthorized");
        return userId;
    }
}
