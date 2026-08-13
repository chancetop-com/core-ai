package ai.core.server.web;

import ai.core.api.server.session.BatchDeleteChatSessionsRequest;
import ai.core.api.server.session.BatchDeleteChatSessionsResponse;
import ai.core.api.server.session.ChatSessionSummaryView;
import ai.core.api.server.session.ChatSessionWebService;
import ai.core.api.server.session.DeleteChatSessionResponse;
import ai.core.api.server.session.ListChatSessionsRequest;
import ai.core.api.server.session.ListChatSessionsResponse;
import ai.core.api.server.session.SubmitSessionFeedbackRequest;
import ai.core.api.server.session.SubmitSessionFeedbackResponse;
import ai.core.api.server.session.UpdateChatSessionTitleRequest;
import ai.core.api.server.session.UpdateChatSessionTitleResponse;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.SessionFeedback;
import ai.core.server.memory.experiment.AgentMemoryExperimentService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
import ai.core.server.session.ChatMessageService;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import core.framework.web.exception.UnauthorizedException;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.UUID;

/**
 * @author stephen
 */
@PermissionsRequired(PermissionCodes.CHAT_USE)
public class ChatSessionWebServiceImpl implements ChatSessionWebService {
    @Inject
    WebContext webContext;
    @Inject
    ChatMessageService chatMessageService;
    @Inject
    SandboxSnapshotService sandboxSnapshotService;
    @Inject
    MongoCollection<SessionFeedback> sessionFeedbackCollection;
    @Inject
    AgentMemoryExperimentService memoryExperimentService;

    @Override
    public ListChatSessionsResponse list(ListChatSessionsRequest request) {
        var userId = userId();
        int offset = request.offset == null ? 0 : request.offset;
        int limit = request.limit == null ? 50 : request.limit;
        var sources = request.sources != null && !request.sources.isEmpty()
            ? Arrays.asList(request.sources.split(","))
            : null;
        var agentIds = request.agentIds != null && !request.agentIds.isEmpty()
            ? Arrays.asList(request.agentIds.split(","))
            : null;
        long total = chatMessageService.countSessions(userId, sources, agentIds);
        var sessions = chatMessageService.listSessions(userId, sources, agentIds, offset, limit, "created_at").stream()
            .map(this::toSummary)
            .toList();
        var response = new ListChatSessionsResponse();
        response.sessions = sessions;
        response.total = total;
        return response;
    }

    @Override
    public ChatSessionSummaryView get(String sessionId) {
        var userId = userId();
        var session = chatMessageService.getSessionMeta(sessionId);
        if (session == null || session.deletedAt != null) throw new NotFoundException("session not found");
        if (session.userId != null && !userId.equals(session.userId)) throw new ForbiddenException("forbidden");
        return toSummary(session);
    }

    @Override
    public DeleteChatSessionResponse delete(String sessionId) {
        var userId = userId();
        var ok = chatMessageService.softDeleteSession(userId, sessionId);
        if (!ok) throw new NotFoundException("session not found");
        sandboxSnapshotService.deleteForSession(sessionId);
        var response = new DeleteChatSessionResponse();
        response.deleted = Boolean.TRUE;
        return response;
    }

    @Override
    public BatchDeleteChatSessionsResponse batchDelete(BatchDeleteChatSessionsRequest request) {
        var userId = userId();
        if (request.sessionIds == null || request.sessionIds.isEmpty()) {
            throw new BadRequestException("session_ids required");
        }
        // Only clean snapshots of sessions actually soft-deleted: batchSoftDelete skips
        // non-owned/nonexistent ids, and snapshot deletion must not outrun that ownership check.
        var deletedIds = chatMessageService.batchSoftDelete(userId, request.sessionIds);
        deletedIds.forEach(sandboxSnapshotService::deleteForSession);
        var response = new BatchDeleteChatSessionsResponse();
        response.deleted = deletedIds.size();
        return response;
    }

    @Override
    public UpdateChatSessionTitleResponse update(String sessionId, UpdateChatSessionTitleRequest request) {
        var userId = userId();
        if (request.title == null || request.title.isBlank()) throw new BadRequestException("title required");
        var ok = chatMessageService.updateSessionTitle(userId, sessionId, request.title);
        if (!ok) throw new NotFoundException("session not found");
        var response = new UpdateChatSessionTitleResponse();
        response.updated = Boolean.TRUE;
        return response;
    }

    @Override
    public SubmitSessionFeedbackResponse submitFeedback(String sessionId, SubmitSessionFeedbackRequest request) {
        var userId = userId();
        var session = chatMessageService.getSessionMeta(sessionId);
        if (session == null || session.deletedAt != null) throw new NotFoundException("session not found");
        if (session.userId != null && !userId.equals(session.userId)) throw new ForbiddenException("forbidden");

        var feedback = new SessionFeedback();
        feedback.id = UUID.randomUUID().toString();
        feedback.sessionId = sessionId;
        feedback.userId = userId;
        feedback.agentId = session.agentId;
        feedback.createdAt = ZonedDateTime.now();

        // Layer 1
        feedback.outcome = request.outcome;

        // Layer 2
        feedback.failureReasons = request.failureReasons;
        feedback.failureDetail = request.failureDetail;

        // Layer 3
        feedback.understandingRating = request.understandingRating;
        feedback.problemSolvingRating = request.problemSolvingRating;
        feedback.toolUsageRating = request.toolUsageRating;
        feedback.communicationRating = request.communicationRating;
        feedback.outcomeRating = request.outcomeRating;

        // Layer 4
        feedback.proactivityFit = request.proactivityFit;
        feedback.decisionFit = request.decisionFit;

        // Layer 5
        feedback.trustLevel = request.trustLevel;

        // Free text
        feedback.comment = request.comment;

        // Auto-collected
        feedback.modelId = request.modelId;
        feedback.tokenCount = request.tokenCount;
        feedback.sessionDurationMs = request.sessionDurationMs;
        feedback.toolCallCount = request.toolCallCount;
        feedback.toolErrorCount = request.toolErrorCount;
        feedback.messageCount = request.messageCount;
        feedback.source = request.source;

        sessionFeedbackCollection.insert(feedback);

        // patch experiment run outcome
        memoryExperimentService.recordOutcome(sessionId, feedback.outcome, feedback.outcomeRating);

        var response = new SubmitSessionFeedbackResponse();
        response.id = feedback.id;
        response.created = Boolean.TRUE;
        return response;
    }

    private ChatSessionSummaryView toSummary(ChatSession s) {
        var view = new ChatSessionSummaryView();
        view.id = s.id;
        view.userId = s.userId;
        view.agentId = s.agentId;
        view.source = s.source;
        view.scheduleId = s.scheduleId;
        view.apiKeyId = s.apiKeyId;
        view.title = s.title;
        view.messageCount = s.messageCount;
        view.createdAt = s.createdAt != null ? s.createdAt.toInstant().toString() : null;
        view.lastMessageAt = s.lastMessageAt != null ? s.lastMessageAt.toInstant().toString() : null;
        return view;
    }

    private String userId() {
        var userId = AuthContext.userId(webContext);
        if (userId == null) throw new UnauthorizedException("unauthorized");
        return userId;
    }
}
