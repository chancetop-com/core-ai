package ai.core.server.sse;

import ai.core.server.gateway.GatewayChatCompletionsSseEvent;
import ai.core.server.gateway.GatewayResponsesSseEvent;
import ai.core.sse.PatchedServerSentEventConfig;
import core.framework.http.HTTPMethod;
import core.framework.web.Request;
import core.framework.web.sse.Channel;
import core.framework.web.sse.ChannelListener;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * @author stephen
 */
class SseEndpointRegistryImplTest {
    @Test
    void registersGatewaySseRoutesWithTheirListenersAndEventStreamAcceptRequirement() {
        var config = mock(PatchedServerSentEventConfig.class);
        var registry = new SseEndpointRegistryImpl(config);
        var chatCompletionsListener = new GatewayChatCompletionsListener();
        var responsesListener = new GatewayResponsesListener();

        registry.register(HTTPMethod.POST, "/api/gateway/v1/chat/completions", GatewayChatCompletionsSseEvent.class, chatCompletionsListener, true);
        registry.register(HTTPMethod.POST, "/api/gateway/v1/responses", GatewayResponsesSseEvent.class, responsesListener, true);

        verify(config).listen(HTTPMethod.POST, "/api/gateway/v1/chat/completions", GatewayChatCompletionsSseEvent.class, chatCompletionsListener);
        verify(config).requireEventStreamAccept(HTTPMethod.POST, "/api/gateway/v1/chat/completions");
        verify(config).listen(HTTPMethod.POST, "/api/gateway/v1/responses", GatewayResponsesSseEvent.class, responsesListener);
        verify(config).requireEventStreamAccept(HTTPMethod.POST, "/api/gateway/v1/responses");
    }

    @Test
    void doesNotRequireEventStreamAcceptWhenTheRouteDoesNotOptIn() {
        var config = mock(PatchedServerSentEventConfig.class);
        var registry = new SseEndpointRegistryImpl(config);
        var listener = new GatewayChatCompletionsListener();

        registry.register(HTTPMethod.POST, "/api/test/events", GatewayChatCompletionsSseEvent.class, listener, false);

        verify(config).listen(HTTPMethod.POST, "/api/test/events", GatewayChatCompletionsSseEvent.class, listener);
        verify(config, never()).requireEventStreamAccept(HTTPMethod.POST, "/api/test/events");
    }

    @Test
    void rejectsDuplicateMethodAndPathBeforeDelegatingAgain() {
        var config = mock(PatchedServerSentEventConfig.class);
        var registry = new SseEndpointRegistryImpl(config);
        var listener = new GatewayChatCompletionsListener();
        registry.register(HTTPMethod.POST, "/api/gateway/v1/chat/completions", GatewayChatCompletionsSseEvent.class, listener, true);

        assertThrows(IllegalStateException.class, () -> registry.register(HTTPMethod.POST, "/api/gateway/v1/chat/completions", GatewayResponsesSseEvent.class, new GatewayResponsesListener(), true));

        verify(config, times(1)).listen(HTTPMethod.POST, "/api/gateway/v1/chat/completions", GatewayChatCompletionsSseEvent.class, listener);
        verify(config, times(1)).requireEventStreamAccept(HTTPMethod.POST, "/api/gateway/v1/chat/completions");
        verifyNoMoreInteractions(config);
    }

    private static final class GatewayChatCompletionsListener implements ChannelListener<GatewayChatCompletionsSseEvent> {
        @Override
        public void onConnect(Request request, Channel<GatewayChatCompletionsSseEvent> channel, String lastEventId) {
        }
    }

    private static final class GatewayResponsesListener implements ChannelListener<GatewayResponsesSseEvent> {
        @Override
        public void onConnect(Request request, Channel<GatewayResponsesSseEvent> channel, String lastEventId) {
        }
    }
}
