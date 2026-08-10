package ai.core.server.web.sse;

import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.server.session.SessionRegistry;
import ai.core.server.web.auth.AuthContext;
import core.framework.web.Request;
import core.framework.web.WebContext;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.sse.Channel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSessionChannelListenerTest {
    @Test
    void validatesAuthenticatedOwnerBeforeConnecting() {
        var listener = listener();
        var request = request("s-1");
        var channel = channel();

        listener.onConnect(request, channel, null);

        var ordered = inOrder(listener.sessionRegistry, listener.sessionChannelService, channel);
        ordered.verify(listener.sessionRegistry).requireAccessible("s-1", "user-1");
        ordered.verify(listener.sessionChannelService).connect(channel, "s-1");
        ordered.verify(channel).join("s-1");
    }

    @Test
    void wrongOwnerDoesNotConnect() {
        var listener = listener();
        var request = request("s-1");
        var channel = channel();
        when(listener.sessionRegistry.requireAccessible("s-1", "user-1"))
                .thenThrow(new ForbiddenException("session is unavailable"));

        assertThrows(ForbiddenException.class, () -> listener.onConnect(request, channel, null));

        verify(listener.sessionChannelService, never()).connect(any(), any());
        verify(channel, never()).join(any());
    }

    private AgentSessionChannelListener listener() {
        var listener = new AgentSessionChannelListener();
        listener.sessionRegistry = mock(SessionRegistry.class);
        listener.sessionChannelService = mock(SessionChannelService.class);
        listener.webContext = mock(WebContext.class);
        when(listener.webContext.get(AuthContext.USER_ID_KEY)).thenReturn("user-1");
        return listener;
    }

    private Request request(String sessionId) {
        var request = mock(Request.class);
        when(request.queryParams()).thenReturn(Map.of("agent-session-id", sessionId));
        return request;
    }

    @SuppressWarnings("unchecked")
    private Channel<SseBaseEvent> channel() {
        var channel = (Channel<SseBaseEvent>) mock(Channel.class);
        when(channel.context()).thenReturn(mock(Channel.Context.class));
        return channel;
    }
}
