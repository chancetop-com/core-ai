package ai.core.server.web.sse;

import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.server.apiuser.ApiUserQuotaService;
import ai.core.server.messaging.CommandPublisher;
import ai.core.server.messaging.SessionCommand;
import ai.core.server.session.SessionRegistry;
import ai.core.server.web.auth.AuthContext;
import core.framework.web.Request;
import core.framework.web.WebContext;
import core.framework.web.exception.NotFoundException;
import core.framework.web.sse.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentMessageStreamChannelListenerTest {
    @Test
    void validatesAuthenticatedOwnerBeforeConnectingAndPublishing() {
        var listener = listener();
        var request = request("s-1");
        var channel = channel();

        listener.onConnect(request, channel, null);

        var ordered = inOrder(listener.sessionRegistry, listener.sessionChannelService, channel,
                listener.commandPublisher);
        ordered.verify(listener.sessionRegistry).requireAccessible("s-1", "user-1");
        ordered.verify(listener.sessionChannelService).connect(channel, "s-1");
        ordered.verify(channel).join("s-1");
        var command = ArgumentCaptor.forClass(SessionCommand.class);
        ordered.verify(listener.commandPublisher).publish(command.capture());
        assertEquals("s-1", command.getValue().sessionId());
        assertEquals("user-1", command.getValue().userId());
        verify(listener.apiUserQuotaService).checkQuota("user-1");
    }

    @Test
    void missingSessionDoesNotConnectOrPublish() {
        var listener = listener();
        var request = request("missing");
        var channel = channel();
        when(listener.sessionRegistry.requireAccessible("missing", "user-1"))
                .thenThrow(new NotFoundException("session not found"));

        assertThrows(NotFoundException.class, () -> listener.onConnect(request, channel, null));

        verify(listener.sessionChannelService, never()).connect(any(), any());
        verify(channel, never()).join(any());
        verify(listener.commandPublisher, never()).publish(any());
    }

    private AgentMessageStreamChannelListener listener() {
        var listener = new AgentMessageStreamChannelListener();
        listener.sessionRegistry = mock(SessionRegistry.class);
        listener.sessionChannelService = mock(SessionChannelService.class);
        listener.commandPublisher = mock(CommandPublisher.class);
        listener.webContext = mock(WebContext.class);
        listener.apiUserQuotaService = mock(ApiUserQuotaService.class);
        when(listener.webContext.get(AuthContext.USER_ID_KEY)).thenReturn("user-1");
        return listener;
    }

    private Request request(String sessionId) {
        var request = mock(Request.class);
        when(request.queryParams()).thenReturn(Map.of("agent-session-id", sessionId));
        when(request.body()).thenReturn(Optional.of(
                "{\"message\":\"hello\"}".getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    @SuppressWarnings("unchecked")
    private Channel<SseBaseEvent> channel() {
        var channel = (Channel<SseBaseEvent>) mock(Channel.class);
        when(channel.context()).thenReturn(mock(Channel.Context.class));
        return channel;
    }
}
