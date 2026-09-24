package ai.core.server.session;

import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.channel.ChannelConfigView;
import ai.core.server.channel.ChannelMessage;
import ai.core.server.channel.ChannelOutboundAdapter;
import ai.core.server.channel.ChannelRegistry;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.User;
import core.framework.mongo.MongoCollection;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionCompletionNotifierTest {
    private static final long START = 1_000_000L;

    @Test
    void notifiesTheOwnerWhenALongTurnEnds() {
        var harness = harness();
        harness.now.set(START + 8 * 60_000L);
        var event = TurnCompleteEvent.of("s-1", "done");
        event.inputTokens = 1_234_567L;
        event.outputTokens = 2_999L;
        event.costUsd = 0.4234;

        harness.listener.onTurnComplete(event);

        var captor = ArgumentCaptor.forClass(ChannelMessage.class);
        verify(harness.adapter).sendMessage(captor.capture(), eq("chan-1"), eq("qqbot:c2c:OPENID"),
                eq("qqbot:c2c:OPENID"), isNull(), any());
        var text = captor.getValue().text;
        assertTrue(text.contains("Session finished — Nightly report"), text);
        assertTrue(text.contains("Duration: 8m 00s · completed"), text);
        assertTrue(text.contains("Cost: $0.42"), text);
        assertTrue(text.contains("tokens 1.2M in / 3K out"), text);
        assertTrue(text.contains("https://core.example/chat?sessionId=s-1"), text);
    }

    @Test
    void skipsWhenThePerChatSwitchIsOff() {
        var harness = harness();
        harness.session.notifyOnComplete = Boolean.FALSE;
        harness.now.set(START + 8 * 60_000L);

        harness.listener.onTurnComplete(TurnCompleteEvent.of("s-1", "done"));

        verify(harness.adapter, never()).sendMessage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void skipsSessionsThatAlreadyReportBack() {
        var harness = harness();
        harness.session.source = "channel";
        harness.now.set(START + 8 * 60_000L);

        harness.listener.onTurnComplete(TurnCompleteEvent.of("s-1", "done"));

        verify(harness.adapter, never()).sendMessage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void skipsWhenTheUserHasNoTarget() {
        var harness = harness();
        harness.user.notifyRecipient = null;
        harness.now.set(START + 8 * 60_000L);

        harness.listener.onTurnComplete(TurnCompleteEvent.of("s-1", "done"));

        verify(harness.adapter, never()).sendMessage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void skipsATurnShorterThanTheUserThreshold() {
        var harness = harness();
        harness.user.notifyMinMinutes = 30;
        harness.now.set(START + 8 * 60_000L);

        harness.listener.onTurnComplete(TurnCompleteEvent.of("s-1", "done"));

        verify(harness.adapter, never()).sendMessage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void skipsATurnShorterThanTheFloorWithoutTouchingTheDatabase() {
        var harness = harness();
        harness.now.set(START + 5_000L);

        harness.listener.onTurnComplete(TurnCompleteEvent.of("s-1", "done"));

        verify(harness.registry, never()).get(any());
        verify(harness.adapter, never()).sendMessage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void cancelledTurnSaysNothing() {
        var harness = harness();
        harness.now.set(START + 30 * 60_000L);

        harness.listener.onTurnComplete(TurnCompleteEvent.cancelled("s-1", "partial"));

        verify(harness.adapter, never()).sendMessage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void failedTurnIsReportedWithItsReason() {
        var harness = harness();
        harness.now.set(START + 6 * 60_000L);

        harness.listener.onError(ErrorEvent.of("s-1", "upstream 500", ""));

        var captor = ArgumentCaptor.forClass(ChannelMessage.class);
        verify(harness.adapter).sendMessage(captor.capture(), eq("chan-1"), eq("qqbot:c2c:OPENID"),
                eq("qqbot:c2c:OPENID"), isNull(), any());
        assertTrue(captor.getValue().text.contains("Session finished — Nightly report"));
        assertTrue(captor.getValue().text.contains("failed — upstream 500"));
    }

    @Test
    void aTurnThatNeverReportedRunningIsSkipped() {
        var harness = harness();
        harness.now.set(START + 30 * 60_000L);
        // a rebuilt session starts with a fresh listener: a turn already in flight has no start to measure
        var listenerWithoutRunning = harness.notifier.listener("s-1");

        listenerWithoutRunning.onTurnComplete(TurnCompleteEvent.of("s-1", "done"));

        verify(harness.adapter, never()).sendMessage(any(), any(), any(), any(), any(), any());
    }

    private Harness harness() {
        var notifier = new SessionCompletionNotifier();
        var registry = mock(SessionRegistry.class);
        @SuppressWarnings("unchecked")
        var users = (MongoCollection<User>) mock(MongoCollection.class);
        var channelConfigStore = mock(ChannelConfigStore.class);
        var channelRegistry = mock(ChannelRegistry.class);
        var adapter = mock(ChannelOutboundAdapter.class);

        var session = new ChatSession();
        session.id = "s-1";
        session.userId = "u-1";
        session.source = "chat";
        session.title = "Nightly report";
        session.notifyOnComplete = Boolean.TRUE;

        var user = new User();
        user.id = "u-1";
        user.name = "owner";
        user.notifyChannelId = "chan-1";
        user.notifyRecipient = "qqbot:c2c:OPENID";

        var channel = new ChannelConfigView();
        channel.channelId = "chan-1";
        channel.channelType = "openclaw";
        channel.enabled = Boolean.TRUE;

        when(registry.get("s-1")).thenReturn(session);
        when(users.get("u-1")).thenReturn(Optional.of(user));
        when(channelConfigStore.load("chan-1")).thenReturn(channel);
        when(channelRegistry.outbound("openclaw")).thenReturn(adapter);

        var now = new AtomicLong(START);
        notifier.sessionRegistry = registry;
        notifier.userCollection = users;
        notifier.channelConfigStore = channelConfigStore;
        notifier.channelRegistry = channelRegistry;
        notifier.publicUrlConfiguration = new PublicUrlConfiguration("https://core.example");
        notifier.clock = now::get;

        var listener = notifier.listener("s-1");
        listener.onStatusChange(StatusChangeEvent.of("s-1", SessionStatus.RUNNING));
        return new Harness(notifier, registry, session, user, adapter, now, listener);
    }

    private record Harness(SessionCompletionNotifier notifier, SessionRegistry registry, ChatSession session, User user,
                           ChannelOutboundAdapter adapter, AtomicLong now, AgentEventListener listener) {
    }
}
