package ai.core.server.share;

import ai.core.api.server.share.CreateSessionShareRequest;
import ai.core.api.server.share.ListSharedMessagesRequest;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.SessionShare;
import ai.core.server.domain.User;
import ai.core.server.messaging.TurnStateRegistry;
import ai.core.server.messaging.TurnStateRegistry.TurnLiveness;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.session.ChatMessageQuery;
import ai.core.server.session.ChatMessageStore;
import ai.core.server.session.ChatSessionStore;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.service.TraceAccessControl;
import ai.core.server.trace.service.TraceService;
import ai.core.server.trace.service.TraceVisibility;
import ai.core.server.trace.service.TraceVisibilityResolver;
import ai.core.server.user.UserStore;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Xander
 */
class SessionShareServiceTest {
    private static final String TOKEN = "a".repeat(43);
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-11-13T08:00:00Z");

    private static SessionShare share() {
        var share = new SessionShare();
        share.id = "share-1";
        share.sessionId = "session-1";
        share.ownerUserId = "owner-1";
        share.token = TOKEN;
        share.title = "Debug session";
        share.maxMessageSeq = 2L;
        share.messageCount = 2L;
        share.createdAt = NOW.minusHours(1);
        share.expiresAt = NOW.plusDays(7);
        share.traceIds = List.of("trace-1");
        share.files = List.of();
        return share;
    }

    private static ChatSession session() {
        var session = new ChatSession();
        session.id = "session-1";
        session.userId = "owner-1";
        session.title = "Debug session";
        return session;
    }

    private static User owner() {
        var user = new User();
        user.id = "owner-1";
        user.status = "active";
        return user;
    }

    private static ChatMessage message(long seq, String role, String traceId) {
        var message = new ChatMessage();
        message.sessionId = "session-1";
        message.seq = seq;
        message.role = role;
        message.traceId = traceId;
        message.content = "test message";
        return message;
    }

    private static Trace trace(String sessionId, String userId) {
        var trace = new Trace();
        trace.traceId = "trace-1";
        trace.sessionId = sessionId;
        trace.userId = userId;
        return trace;
    }

    private final SessionShareService service = new SessionShareService();
    private final SessionShareStore shares = mock(SessionShareStore.class);
    private final ChatSessionStore sessions = mock(ChatSessionStore.class);
    private final ChatMessageStore messages = mock(ChatMessageStore.class);
    private final UserStore users = mock(UserStore.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final TraceService traces = mock(TraceService.class);
    private final TurnStateRegistry turns = mock(TurnStateRegistry.class);
    private final SessionShareFileService files = mock(SessionShareFileService.class);
    private final TraceVisibilityResolver visibility = mock(TraceVisibilityResolver.class);
    private final TraceAccessControl access = mock(TraceAccessControl.class);
    private final ChatSession session = session();
    private final User owner = owner();
    private final ChatMessage prompt = message(1L, "user", null);
    private final ChatMessage reply = message(2L, "agent", "trace-1");

    @BeforeEach
    void setUp() {
        service.shareStore = shares;
        service.sessionStore = sessions;
        service.messageStore = messages;
        service.userStore = users;
        service.permissionService = permissions;
        service.traceService = traces;
        service.turnStateRegistry = turns;
        service.fileService = files;
        service.visibilityResolver = visibility;
        service.traceAccessControl = access;
        service.publicUrl = new PublicUrlConfiguration("https://core.example");
        service.clock = Clock.fixed(Instant.parse("2026-11-13T08:00:00Z"), ZoneOffset.UTC);
        service.projection = new SessionShareProjection();
        service.projection.fileService = files;
        when(sessions.get("session-1")).thenReturn(Optional.of(session));
        when(users.get("owner-1")).thenReturn(Optional.of(owner));
        when(permissions.has("owner-1", PermissionCodes.TRACE_VIEW)).thenReturn(Boolean.TRUE);
        when(visibility.resolve("owner-1")).thenReturn(TraceVisibility.of(Set.of("owner-1")));
        when(access.canRead(any(), eq("owner-1"), any())).thenReturn(Boolean.TRUE);
        when(turns.liveness("session-1")).thenReturn(TurnLiveness.NOT_RUNNING);
        when(messages.latestSeq("session-1")).thenReturn(2L);
        when(messages.count(any())).thenReturn(2L);
        when(messages.find(any())).thenAnswer(invocation -> {
            ChatMessageQuery query = invocation.getArgument(0);
            return query.ascending ? List.of(prompt, reply) : List.of(reply);
        });
        when(files.capture(any(), any())).thenReturn(List.of());
        when(files.rewrite(anyString(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(traces.get("trace-1")).thenReturn(trace("session-1", "owner-1"));
        when(shares.replace(any(), isNull())).thenReturn(Boolean.TRUE);
    }

    @Test
    void createsDefaultSevenDayBoundedShareWithRandomToken() {
        var result = service.create("session-1", "owner-1", new CreateSessionShareRequest());

        var captured = ArgumentCaptor.forClass(SessionShare.class);
        verify(shares).replace(captured.capture(), isNull());
        var share = captured.getValue();
        assertEquals(43, share.token.length());
        assertTrue(share.token.matches("[A-Za-z0-9_-]{43}"));
        assertEquals(2L, share.maxMessageSeq);
        assertEquals(2L, share.messageCount);
        assertEquals(List.of("trace-1"), share.traceIds);
        assertEquals(NOW.plusDays(7), result.expiresAt);
        assertEquals("https://core.example/shared/sessions/" + share.token, result.url);
        assertNotNull(share.id);
    }

    @Test
    void ownerPreviewIncludesAttachmentsReferencedByPersistedMessages() {
        service.current("session-1", "owner-1");
        verify(files).capture(session, List.of(prompt, reply));
    }

    @Test
    void rejectsUnapprovedExpiryAndAnotherOwner() {
        var request = new CreateSessionShareRequest();
        request.expiresInDays = 365;
        assertThrows(BadRequestException.class, () -> service.create("session-1", "owner-1", request));
        assertThrows(NotFoundException.class, () -> service.create("session-1", "other-owner", null));
        verify(shares, never()).replace(any(), any());
    }

    @Test
    void checksTracePermissionEvenWhenAnActiveShareAlreadyExists() {
        when(shares.getBySessionId("session-1")).thenReturn(Optional.of(share()));
        when(permissions.has("owner-1", PermissionCodes.TRACE_VIEW)).thenReturn(Boolean.FALSE);

        assertThrows(ForbiddenException.class, () -> service.create("session-1", "owner-1", null));
        verify(shares, never()).replace(any(), any());
    }

    @Test
    void reusesActiveShareEvenWhileNewTurnIsRunning() {
        when(shares.getBySessionId("session-1")).thenReturn(Optional.of(share()));
        when(turns.liveness("session-1")).thenReturn(TurnLiveness.RUNNING);

        var view = service.create("session-1", "owner-1", null);

        assertEquals("share-1", view.id);
        verify(messages, never()).find(any());
        verify(shares, never()).replace(any(), any());
    }

    @Test
    void rejectsRunningOrUnknownTurnState() {
        when(turns.liveness("session-1")).thenReturn(TurnLiveness.RUNNING, TurnLiveness.UNKNOWN);

        assertThrows(ConflictException.class, () -> service.create("session-1", "owner-1", null));
        assertThrows(ConflictException.class, () -> service.create("session-1", "owner-1", null));
        verify(shares, never()).replace(any(), any());
    }

    @Test
    void rejectsUnansweredLatestUserMessage() {
        doReturn(List.of(prompt)).when(messages).find(any());

        assertThrows(ConflictException.class, () -> service.create("session-1", "owner-1", null));
        verify(shares, never()).replace(any(), any());
    }

    @Test
    void rejectsNewMessagePersistedDuringManifestCapture() {
        when(messages.latestSeq("session-1")).thenReturn(3L);

        assertThrows(ConflictException.class, () -> service.create("session-1", "owner-1", null));
        verify(shares, never()).replace(any(), any());
    }

    @Test
    void rejectsTraceFromAnotherSessionEvenWhenOwnerCanReadIt() {
        when(traces.get("trace-1")).thenReturn(trace("other-session", "owner-1"));

        assertThrows(ForbiddenException.class, () -> service.create("session-1", "owner-1", null));
        verify(shares, never()).replace(any(), any());
    }

    @Test
    void keepsMissingTraceAsCandidateButDoesNotMakeItReadable() {
        when(traces.get("trace-1")).thenReturn(null);
        service.create("session-1", "owner-1", null);
        var captured = ArgumentCaptor.forClass(SessionShare.class);
        verify(shares).replace(captured.capture(), isNull());

        assertEquals(List.of("trace-1"), captured.getValue().traceIds);
        assertThrows(NotFoundException.class, () -> service.requireTrace(captured.getValue(), "trace-1"));
    }

    @Test
    void replacesRevokedShareWithFreshIdAndToken() {
        var revoked = share();
        revoked.revokedAt = NOW.minusMinutes(1);
        when(shares.getBySessionId("session-1")).thenReturn(Optional.of(revoked));
        when(shares.replace(any(), eq("share-1"))).thenReturn(Boolean.TRUE);

        var result = service.create("session-1", "owner-1", null);

        assertNotEquals("share-1", result.id);
        assertFalse(result.url.endsWith(TOKEN));
        verify(shares).replace(any(), eq("share-1"));
    }

    @Test
    void returnsWinningConcurrentCreateWithoutOverwritingIt() {
        when(shares.replace(any(), isNull())).thenReturn(Boolean.FALSE);
        when(shares.getBySessionId("session-1")).thenReturn(Optional.empty()).thenReturn(Optional.of(share()));

        var result = service.create("session-1", "owner-1", null);

        assertEquals("share-1", result.id);
    }

    @Test
    void publicReadsRequireCurrentOwnerPermissionAndActiveAccount() {
        when(shares.getByToken(TOKEN)).thenReturn(Optional.of(share()));
        assertEquals("share-1", service.requirePublic(TOKEN).id);
        when(permissions.has("owner-1", PermissionCodes.TRACE_VIEW)).thenReturn(Boolean.FALSE);
        assertThrows(NotFoundException.class, () -> service.requirePublic(TOKEN));
        when(permissions.has("owner-1", PermissionCodes.TRACE_VIEW)).thenReturn(Boolean.TRUE);
        owner.status = "pending";
        assertThrows(NotFoundException.class, () -> service.requirePublic(TOKEN));
    }

    @Test
    void publicReadsRejectExpiredRevokedDeletedAndMalformedLinks() {
        var share = share();
        when(shares.getByToken(TOKEN)).thenReturn(Optional.of(share));
        share.expiresAt = NOW;
        assertThrows(NotFoundException.class, () -> service.requirePublic(TOKEN));
        share.expiresAt = NOW.plusDays(1);
        share.revokedAt = NOW;
        assertThrows(NotFoundException.class, () -> service.requirePublic(TOKEN));
        share.revokedAt = null;
        session.deletedAt = NOW;
        assertThrows(NotFoundException.class, () -> service.requirePublic(TOKEN));
        assertThrows(NotFoundException.class, () -> service.requirePublic("not-a-token"));
        verify(shares, never()).getByToken("not-a-token");
    }

    @Test
    void publicMessagesAlwaysApplyFrozenSequenceAndBoundPagination() {
        var share = share();
        share.messageCount = 4L;
        when(shares.getByToken(TOKEN)).thenReturn(Optional.of(share));
        var request = new ListSharedMessagesRequest();
        request.limit = 2;

        var result = service.messages(TOKEN, request);

        var query = ArgumentCaptor.forClass(ChatMessageQuery.class);
        verify(messages).find(query.capture());
        assertEquals(2L, query.getValue().maxSeq);
        assertEquals("session-1", query.getValue().sessionId);
        assertEquals(2, query.getValue().limit);
        assertEquals(2, result.nextOffset);
        assertEquals("trace-1", result.messages.getLast().traceId);
        request.limit = 101;
        assertThrows(BadRequestException.class, () -> service.messages(TOKEN, request));
        request.limit = 2;
        request.offset = -1;
        assertThrows(BadRequestException.class, () -> service.messages(TOKEN, request));
    }

    @Test
    void anEmptyMessagePageHasNoNextCursor() {
        when(shares.getByToken(TOKEN)).thenReturn(Optional.of(share()));
        doReturn(List.of()).when(messages).find(any());

        assertNull(service.messages(TOKEN, null).nextOffset);
    }

    @Test
    void traceRequestsRequireExactManifestAndOwnerSession() {
        var share = share();
        assertSame(traces.get("trace-1"), service.requireTrace(share, "trace-1"));
        assertThrows(NotFoundException.class, () -> service.requireTrace(share, "trace-unshared"));
        when(traces.get("trace-1")).thenReturn(trace("session-1", "different-owner"));
        assertThrows(NotFoundException.class, () -> service.requireTrace(share, "trace-1"));
    }

    @Test
    void revokeUsesOwnerAndExactShareIdAndCannotBeCalledByAnotherUser() {
        service.revoke("session-1", "old-share", "owner-1");
        verify(shares).revoke("session-1", "old-share", NOW);
        assertThrows(NotFoundException.class, () -> service.revoke("session-1", "share-1", "other-owner"));
    }

    @Test
    void ownerMayManageExistingShareAfterLosingTraceViewButOtherUsersMayNot() {
        when(shares.getBySessionId("session-1")).thenReturn(Optional.of(share()));
        when(permissions.has("owner-1", PermissionCodes.TRACE_VIEW)).thenReturn(Boolean.FALSE);

        assertEquals("share-1", service.current("session-1", "owner-1").share.id);
        service.revoke("session-1", "share-1", "owner-1");
        verify(shares).revoke("session-1", "share-1", NOW);
        assertThrows(NotFoundException.class, () -> service.current("session-1", "other-owner"));
    }

}
