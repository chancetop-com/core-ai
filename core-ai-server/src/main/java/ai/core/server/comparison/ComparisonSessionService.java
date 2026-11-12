package ai.core.server.comparison;

import ai.core.api.server.comparison.ComparisonSessionContextView;
import ai.core.api.server.comparison.ComparisonVoteView;
import ai.core.api.server.comparison.CreateComparisonSessionsResponse;
import ai.core.api.server.comparison.SubmitComparisonVoteRequest;
import ai.core.server.apiuser.ApiUserQuotaService;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.comparison.domain.AgentComparison;
import ai.core.server.comparison.domain.ComparisonStatus;
import ai.core.server.comparison.domain.ComparisonVote;
import ai.core.server.comparison.domain.ComparisonVoteChoice;
import ai.core.server.domain.ChatSession;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.SessionRegistry;
import ai.core.server.systemprompt.SystemPromptService;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The tester-facing side of a comparison: session pair creation (frozen variant definitions), the
 * per-session comparison context and per-turn votes.
 *
 * @author stephen
 */
public class ComparisonSessionService {

    private static ComparisonVoteChoice parseChoice(String value) {
        if (value == null || value.isBlank()) throw new BadRequestException("chosen required");
        try {
            return ComparisonVoteChoice.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("chosen must be A, B, TIE or NEITHER", "BAD_REQUEST", e);
        }
    }

    @Inject
    AgentComparisonStore comparisonStore;
    @Inject
    ComparisonVoteStore voteStore;
    @Inject
    ai.core.server.session.ChatSessionStore chatSessionStore;
    @Inject
    ai.core.server.session.ChatMessageStore chatMessageStore;
    @Inject
    ai.core.server.user.UserStore userStore;
    @Inject
    AgentSessionManager sessionManager;
    @Inject
    SessionRegistry sessionRegistry;
    @Inject
    SystemPromptService systemPromptService;
    @Inject
    PermissionService permissionService;
    @Inject
    ApiUserQuotaService apiUserQuotaService;

    public CreateComparisonSessionsResponse createSessions(String comparisonId, String userId) {
        var comparison = requireComparison(comparisonId);
        if (comparison.status != ComparisonStatus.RUNNING) {
            throw new BadRequestException("comparison is not running");
        }
        ComparisonAccess.requireParticipant(comparison, userId);
        permissionService.check(userId, PermissionService.RESOURCE_TYPE_AGENT, comparison.agentId);
        apiUserQuotaService.checkQuota(userId);

        var pairId = UUID.randomUUID().toString();
        var primarySessionId = createArm(comparison, "a", pairId, userId);
        String secondarySessionId;
        try {
            secondarySessionId = createArm(comparison, "b", pairId, userId);
        } catch (RuntimeException | Error e) {
            // a pair is all-or-nothing: a lone half would still burn budget and confuse the tester
            sessionManager.abortSessionCreation(primarySessionId);
            throw e;
        }

        var response = new CreateComparisonSessionsResponse();
        response.comparisonId = comparison.id;
        response.pairId = pairId;
        response.primarySessionId = primarySessionId;
        response.secondarySessionId = secondarySessionId;
        return response;
    }

    private String createArm(AgentComparison comparison, String variantKey, String pairId, String userId) {
        var variant = ComparisonVariantDefinitionFactory.variantOf(comparison, variantKey);
        var definition = ComparisonVariantDefinitionFactory.build(comparison, variant, userId, systemPromptService);
        var result = sessionManager.createSessionFromAgent(definition, null, userId, "chat");
        // stamped before the pair ids are returned to the client, so no other actor can rebuild the
        // session from the live agent definition in between (a crash here falls back to the live path)
        sessionRegistry.recordComparison(result.sessionId(), comparison.id, variantKey, pairId);
        return result.sessionId();
    }

    public ComparisonSessionContextView bySession(String sessionId, String userId) {
        var meta = sessionRegistry.get(sessionId);
        if (meta == null || meta.deletedAt != null) throw new NotFoundException("session not found");
        if (meta.userId != null && !meta.userId.equals(userId)) throw new ForbiddenException("session is unavailable");
        if (meta.comparisonId == null) {
            var plain = new ComparisonSessionContextView();
            plain.agentId = meta.agentId;
            return plain;
        }
        var comparison = comparisonStore.get(meta.comparisonId).orElse(null);
        if (comparison == null) throw new NotFoundException("comparison not found, id=" + meta.comparisonId);

        var pairRows = pairSessions(meta.comparisonPairId);
        var sessionA = pairRows.stream().filter(row -> "a".equals(row.comparisonVariant)).findFirst().orElse(null);
        var sessionB = pairRows.stream().filter(row -> "b".equals(row.comparisonVariant)).findFirst().orElse(null);

        var view = new ComparisonSessionContextView();
        view.comparisonId = comparison.id;
        view.pairId = meta.comparisonPairId;
        view.agentId = comparison.agentId;
        view.agentName = comparison.agentName;
        view.status = comparison.status != null ? comparison.status.name() : null;
        view.variant = meta.comparisonVariant;
        view.primarySessionId = sessionA != null ? sessionA.id : null;
        view.secondarySessionId = sessionB != null ? sessionB.id : null;
        view.blind = !ComparisonAccess.canManage(userStore, comparison, userId);
        view.budgetUsd = comparison.budgetUsd;
        view.votes = pairVotes(comparison.id, sessionA);
        return view;
    }

    public ComparisonVoteView vote(String comparisonId, SubmitComparisonVoteRequest request, String userId) {
        var comparison = requireComparison(comparisonId);
        if (comparison.status == ComparisonStatus.DRAFT) {
            throw new BadRequestException("comparison has not started");
        }
        ComparisonAccess.requireParticipant(comparison, userId);
        if (request == null || request.sessionA == null || request.sessionA.isBlank()) {
            throw new BadRequestException("session_a required");
        }
        if (request.turn == null || request.turn < 1) {
            throw new BadRequestException("turn required");
        }
        var sessionA = sessionRegistry.get(request.sessionA);
        if (sessionA == null || !userId.equals(sessionA.userId) || !comparisonId.equals(sessionA.comparisonId)) {
            throw new ForbiddenException("session is unavailable");
        }
        var sessionB = pairSessions(sessionA.comparisonPairId).stream()
            .filter(row -> !row.id.equals(sessionA.id)).findFirst().orElse(null);
        validateMessage(request.messageA, sessionA.id);
        validateMessage(request.messageB, sessionB != null ? sessionB.id : null);

        var vote = findVote(comparisonId, sessionA.id, request.turn);
        var isNew = vote == null;
        if (isNew) {
            vote = new ComparisonVote();
            vote.id = UUID.randomUUID().toString();
            vote.comparisonId = comparisonId;
            vote.sessionA = sessionA.id;
            vote.turn = request.turn;
            vote.userId = userId;
            vote.createdAt = ZonedDateTime.now();
        }
        vote.pairId = sessionA.comparisonPairId;
        vote.sessionB = sessionB != null ? sessionB.id : null;
        vote.chosen = parseChoice(request.chosen);
        vote.comment = request.comment;
        vote.messageA = request.messageA;
        vote.messageB = request.messageB;
        if (isNew) {
            insertVote(vote, comparisonId, sessionA.id, request.turn);
        } else {
            voteStore.replace(vote);
        }
        return ComparisonViewAssembler.toVoteView(vote);
    }

    private void insertVote(ComparisonVote vote, String comparisonId, String sessionAId, Long turn) {
        if (voteStore.insertIfAbsent(vote)) return;
        // concurrent vote on the same turn: the unique anchor made one of them lose, fold into it
        var existing = findVote(comparisonId, sessionAId, turn);
        if (existing == null) throw new IllegalStateException("concurrent comparison vote disappeared");
        existing.pairId = vote.pairId;
        existing.sessionB = vote.sessionB;
        existing.chosen = vote.chosen;
        existing.comment = vote.comment;
        existing.messageA = vote.messageA;
        existing.messageB = vote.messageB;
        voteStore.replace(existing);
    }

    private ComparisonVote findVote(String comparisonId, String sessionAId, Long turn) {
        return voteStore.find(comparisonId, sessionAId, turn).orElse(null);
    }

    private List<ComparisonVoteView> pairVotes(String comparisonId, ChatSession sessionA) {
        if (sessionA == null) return List.of();
        return voteStore.findBySessionA(comparisonId, sessionA.id).stream()
            .map(ComparisonViewAssembler::toVoteView).toList();
    }

    private List<ChatSession> pairSessions(String pairId) {
        if (pairId == null) return List.of();
        var query = new ai.core.server.session.ChatSessionQuery();
        query.comparisonPairId = pairId;
        return chatSessionStore.find(query);
    }

    private void validateMessage(String messageId, String sessionId) {
        if (messageId == null || messageId.isBlank() || sessionId == null) return;
        var message = chatMessageStore.get(messageId).orElse(null);
        if (message == null || !sessionId.equals(message.sessionId) || !"agent".equals(message.role)) {
            throw new BadRequestException("vote message does not belong to the session");
        }
    }

    private AgentComparison requireComparison(String id) {
        return comparisonStore.get(id)
            .orElseThrow(() -> new NotFoundException("comparison not found, id=" + id));
    }
}





