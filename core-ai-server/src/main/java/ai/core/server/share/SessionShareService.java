package ai.core.server.share;

import ai.core.api.server.share.CreateSessionShareRequest;
import ai.core.api.server.share.ListSharedMessagesRequest;
import ai.core.api.server.share.SessionShareStatusResponse;
import ai.core.api.server.share.SessionShareView;
import ai.core.api.server.share.SharedSessionMessagesResponse;
import ai.core.api.server.share.SharedSessionView;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.SessionShare;
import ai.core.server.domain.SessionShareFile;
import ai.core.server.messaging.TurnStateRegistry;
import ai.core.server.messaging.TurnStateRegistry.TurnLiveness;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.session.ChatMessageQuery;
import ai.core.server.session.ChatMessageStore;
import ai.core.server.session.ChatSessionStore;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.service.TraceAccessControl;
import ai.core.server.trace.service.TraceService;
import ai.core.server.trace.service.TraceVisibilityResolver;
import ai.core.server.user.UserStore;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * @author Xander
 */
public class SessionShareService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_MESSAGES = 10_000;
    private static final int MAX_FILES = 1000;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<Integer> EXPIRY_DAYS = Set.of(1, 7, 30);

    private static ChatMessageQuery boundedQuery(String sessionId, long maxSeq) {
        var query = new ChatMessageQuery();
        query.sessionId = sessionId;
        query.maxSeq = maxSeq;
        return query;
    }

    private static String newToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static NotFoundException unavailable() {
        return new NotFoundException("shared content is unavailable");
    }

    @Inject
    SessionShareStore shareStore;
    @Inject
    ChatSessionStore sessionStore;
    @Inject
    ChatMessageStore messageStore;
    @Inject
    UserStore userStore;
    @Inject
    PermissionService permissionService;
    @Inject
    TraceVisibilityResolver visibilityResolver;
    @Inject
    TraceAccessControl traceAccessControl;
    @Inject
    TraceService traceService;
    @Inject
    TurnStateRegistry turnStateRegistry;
    @Inject
    SessionShareFileService fileService;
    @Inject
    SessionShareProjection projection;
    @Inject
    PublicUrlConfiguration publicUrl;

    Clock clock = Clock.systemUTC();

    public SessionShareView create(String sessionId, String userId, CreateSessionShareRequest request) {
        int days = request == null || request.expiresInDays == null ? 7 : request.expiresInDays;
        if (!EXPIRY_DAYS.contains(days)) throw new BadRequestException("expiry must be 1, 7 or 30 days");
        var session = requireOwned(sessionId, userId);
        requireOwnerPermission(userId, false);
        var existing = shareStore.getBySessionId(sessionId).orElse(null);
        if (active(existing)) return toView(existing);
        requireIdle(sessionId);
        var boundary = latestCompletedMessage(sessionId);
        var query = boundedQuery(sessionId, boundary.seq);
        long count = messageStore.count(query);
        if (count > MAX_MESSAGES) throw new BadRequestException("session exceeds the sharing limit of 10000 messages");
        query.limit = MAX_MESSAGES;
        var messages = messageStore.find(query);
        var share = new SessionShare();
        share.id = UUID.randomUUID().toString();
        share.sessionId = sessionId;
        share.ownerUserId = userId;
        share.token = newToken();
        share.title = session.title;
        share.maxMessageSeq = boundary.seq;
        share.messageCount = count;
        share.createdAt = ZonedDateTime.now(clock);
        share.expiresAt = share.createdAt.plusDays(days);
        share.traceIds = captureTraceIds(messages, share);
        share.files = fileService.capture(session, messages);
        if (share.files.size() > MAX_FILES) throw new BadRequestException("session exceeds the sharing limit of 1000 files");
        requireIdle(sessionId);
        var latest = latestCompletedMessage(sessionId);
        if (!Objects.equals(latest.seq, share.maxMessageSeq) || messageStore.latestSeq(sessionId) != share.maxMessageSeq
            || messageStore.count(query) != count) {
            throw new ConflictException("session changed while preparing the share; refresh and try again");
        }
        requireOwned(sessionId, userId);
        if (!shareStore.replace(share, existing == null ? null : existing.id)) {
            var current = shareStore.getBySessionId(sessionId).orElse(null);
            if (active(current)) return toView(current);
            throw new ConflictException("share changed; refresh and try again");
        }
        return toView(share);
    }

    public SessionShareStatusResponse current(String sessionId, String userId) {
        var session = requireOwned(sessionId, userId);
        var share = shareStore.getBySessionId(sessionId).orElse(null);
        var result = new SessionShareStatusResponse();
        if (share != null) result.share = toView(share);
        result.artifacts = active(share)
            ? projection.artifacts(share.files, share)
            : projection.artifacts(previewFiles(session), null);
        return result;
    }

    private List<SessionShareFile> previewFiles(ChatSession session) {
        var query = boundedQuery(session.id, messageStore.latestSeq(session.id));
        query.limit = MAX_MESSAGES;
        return fileService.capture(session, messageStore.find(query));
    }

    public void revoke(String sessionId, String shareId, String userId) {
        requireOwned(sessionId, userId);
        shareStore.revoke(sessionId, shareId, ZonedDateTime.now(clock));
    }

    public SessionShare requirePublic(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw unavailable();
        var share = shareStore.getByToken(token).orElseThrow(SessionShareService::unavailable);
        if (!active(share)) throw unavailable();
        var session = sessionStore.get(share.sessionId).orElseThrow(SessionShareService::unavailable);
        if (session.deletedAt != null || !Objects.equals(session.userId, share.ownerUserId)) throw unavailable();
        requireOwnerPermission(share.ownerUserId, true);
        return share;
    }

    public SharedSessionView get(String token) {
        var share = requirePublic(token);
        var view = new SharedSessionView();
        view.title = projection.text(share.title, share);
        view.maxMessageSeq = share.maxMessageSeq;
        view.messageCount = share.messageCount;
        view.createdAt = share.createdAt;
        view.expiresAt = share.expiresAt;
        view.artifacts = projection.artifacts(share.files, share);
        return view;
    }

    public SharedSessionMessagesResponse messages(String token, ListSharedMessagesRequest request) {
        var share = requirePublic(token);
        int offset = request == null || request.offset == null ? 0 : request.offset;
        int limit = request == null || request.limit == null ? DEFAULT_PAGE_SIZE : request.limit;
        if (offset < 0 || offset > MAX_MESSAGES || limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new BadRequestException("invalid message page; limit must be between 1 and 100");
        }
        var query = boundedQuery(share.sessionId, share.maxMessageSeq);
        query.offset = offset;
        query.limit = limit;
        var records = messageStore.find(query);
        var response = new SharedSessionMessagesResponse();
        response.messages = records.stream().map(message -> projection.message(message, share)).toList();
        int next = offset + records.size();
        if (!records.isEmpty() && next < share.messageCount) response.nextOffset = next;
        return response;
    }

    Trace requireTrace(SessionShare share, String traceId) {
        if (traceId == null || share.traceIds == null || !share.traceIds.contains(traceId)) throw unavailable();
        var trace = traceService.get(traceId);
        if (!traceAllowed(trace, share) || !traceId.equals(trace.traceId)) throw unavailable();
        return trace;
    }

    private ChatSession requireOwned(String sessionId, String userId) {
        var session = sessionStore.get(sessionId).orElseThrow(SessionShareService::unavailable);
        if (session.deletedAt != null || userId == null || !userId.equals(session.userId)) throw unavailable();
        return session;
    }

    private void requireOwnerPermission(String userId, boolean anonymous) {
        var user = userStore.get(userId).orElse(null);
        if (user == null || !"active".equals(user.status) || !permissionService.has(userId, PermissionCodes.TRACE_VIEW)) {
            if (anonymous) throw unavailable();
            throw new ForbiddenException("active account and trace.view permission required to share");
        }
    }

    private void requireIdle(String sessionId) {
        if (turnStateRegistry.liveness(sessionId) != TurnLiveness.NOT_RUNNING) {
            throw new ConflictException("wait until the current turn finishes before sharing");
        }
    }

    private ChatMessage latestCompletedMessage(String sessionId) {
        var query = new ChatMessageQuery();
        query.sessionId = sessionId;
        query.ascending = false;
        query.limit = 1;
        var messages = messageStore.find(query);
        if (messages.isEmpty() || !"agent".equals(messages.getFirst().role) || messages.getFirst().seq == null) {
            throw new ConflictException("a persisted completed turn is required before sharing");
        }
        return messages.getFirst();
    }

    private List<String> captureTraceIds(List<ChatMessage> messages, SessionShare share) {
        var ids = new LinkedHashSet<String>();
        for (var message : messages) {
            if (!"agent".equals(message.role) || message.traceId == null || message.traceId.isBlank()) continue;
            var trace = traceService.get(message.traceId);
            if (trace != null && !traceAllowed(trace, share)) {
                throw new ForbiddenException("a session trace is outside the owner's readable scope");
            }
            ids.add(message.traceId);
        }
        return List.copyOf(ids);
    }

    private boolean traceAllowed(Trace trace, SessionShare share) {
        return trace != null && Objects.equals(trace.sessionId, share.sessionId)
            && Objects.equals(trace.userId, share.ownerUserId)
            && traceAccessControl.canRead(trace, share.ownerUserId, visibilityResolver.resolve(share.ownerUserId));
    }

    private SessionShareView toView(SessionShare share) {
        var view = new SessionShareView();
        view.id = share.id;
        view.sessionId = share.sessionId;
        view.title = share.title;
        view.url = active(share) ? publicUrl.value() + "/shared/sessions/" + share.token : null;
        view.maxMessageSeq = share.maxMessageSeq;
        view.messageCount = share.messageCount;
        view.createdAt = share.createdAt;
        view.expiresAt = share.expiresAt;
        view.revokedAt = share.revokedAt;
        return view;
    }

    private boolean active(SessionShare share) {
        return share != null && share.revokedAt == null && share.expiresAt != null
            && share.expiresAt.isAfter(ZonedDateTime.now(clock));
    }

}
