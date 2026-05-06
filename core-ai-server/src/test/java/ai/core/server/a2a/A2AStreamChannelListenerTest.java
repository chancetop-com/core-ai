package ai.core.server.a2a;

import ai.core.api.a2a.SendMessageRequest;
import ai.core.api.a2a.StreamResponse;
import ai.core.server.web.auth.RequestAuthenticator;
import core.framework.web.Request;
import core.framework.web.sse.Channel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2AStreamChannelListenerTest {
    @Test
    void authenticatesStreamRequestAndPassesUserId() {
        var listener = new A2AStreamChannelListener();
        listener.requestAuthenticator = mock(RequestAuthenticator.class);
        listener.a2aService = mock(ServerA2AService.class);

        var request = mock(Request.class);
        when(request.body()).thenReturn(Optional.of("""
                {"tenant":"agent-1","message":{"role":"ROLE_USER","parts":[{"text":"hello"}]}}
                """.getBytes(StandardCharsets.UTF_8)));
        when(request.queryParams()).thenReturn(Map.of());
        when(listener.requestAuthenticator.authenticate(request)).thenReturn("user-1");

        @SuppressWarnings("unchecked")
        var channel = (Channel<StreamResponse>) mock(Channel.class);
        listener.onConnect(request, channel, null);

        verify(listener.requestAuthenticator).authenticate(request);
        verify(listener.a2aService).stream(eq("agent-1"), any(SendMessageRequest.class), eq("user-1"), any(), any());
    }
}
