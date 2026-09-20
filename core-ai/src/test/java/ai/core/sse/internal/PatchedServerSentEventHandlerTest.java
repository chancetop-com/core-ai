package ai.core.sse.internal;

import core.framework.http.HTTPMethod;
import core.framework.internal.web.request.RequestImpl;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.xnio.XnioIoThread;
import org.xnio.channels.StreamSinkChannel;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchedServerSentEventHandlerTest {
    private static final HttpString POST = new HttpString("POST");
    private PatchedServerSentEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PatchedServerSentEventHandler(null, null, null);
        handler.add(HTTPMethod.POST, "/api/gateway/v1/chat/completions", Object.class, (request, channel, lastEventId) -> {
        }, new PatchedServerSentEventContextImpl<>());
        handler.add(HTTPMethod.POST, "/api/cli/v1/chat/completions", Object.class, (request, channel, lastEventId) -> {
        }, new PatchedServerSentEventContextImpl<>());
        handler.requireEventStreamAccept(HTTPMethod.POST, "/api/gateway/v1/chat/completions");
    }

    @Test
    void acceptGatedRouteRequiresEventStreamAccept() {
        assertTrue(handler.check(POST, "/api/gateway/v1/chat/completions", headers("text/event-stream")));
        assertTrue(handler.check(POST, "/api/gateway/v1/chat/completions", headers("application/json, text/event-stream")));
        assertFalse(handler.check(POST, "/api/gateway/v1/chat/completions", headers("application/json")));
        assertFalse(handler.check(POST, "/api/gateway/v1/chat/completions", headers(null)));
    }

    @Test
    void nonGatedRouteAcceptsAnyRequest() {
        assertTrue(handler.check(POST, "/api/cli/v1/chat/completions", headers("application/json")));
        assertTrue(handler.check(POST, "/api/cli/v1/chat/completions", headers(null)));
    }

    @Test
    void unknownRouteIsRejected() {
        assertFalse(handler.check(POST, "/api/unknown", headers("text/event-stream")));
    }

    @Test
    void sendErrorAndCloseStopsWhenSinkMakesNoProgress() throws Exception {
        var exchange = Mockito.mock(HttpServerExchange.class);
        var sink = Mockito.mock(StreamSinkChannel.class);
        stubIoThread(sink);
        Mockito.when(exchange.isResponseComplete()).thenReturn(Boolean.FALSE);

        var writeCalls = new AtomicInteger();
        Mockito.when(sink.write(ArgumentMatchers.any(ByteBuffer.class))).thenAnswer(invocation -> {
            // socket send buffer is full, the error message cannot be delivered
            if (writeCalls.incrementAndGet() > 5) throw new IllegalStateException("error write loop kept writing after the sink made no progress");
            return 0;
        });

        handler.sendErrorAndClose(channel(exchange, sink), sink, "data: error\n\n");

        assertEquals(1, writeCalls.get(), "error write loop must stop as soon as the sink made no progress");
        Mockito.verify(exchange).endExchange();
    }

    @Test
    void sendErrorAndCloseWritesWholeMessageWhenSinkAccepts() throws Exception {
        var exchange = Mockito.mock(HttpServerExchange.class);
        var sink = Mockito.mock(StreamSinkChannel.class);
        stubIoThread(sink);
        Mockito.when(exchange.isResponseComplete()).thenReturn(Boolean.FALSE);

        var delivered = new StringBuilder();
        Mockito.when(sink.write(ArgumentMatchers.any(ByteBuffer.class))).thenAnswer(invocation -> {
            var buffer = invocation.getArgument(0, ByteBuffer.class);
            var bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            delivered.append(new String(bytes, StandardCharsets.UTF_8));
            return bytes.length;
        });

        handler.sendErrorAndClose(channel(exchange, sink), sink, "data: error\n\n");

        assertEquals("data: error\n\n", delivered.toString());
        Mockito.verify(sink).flush();
    }

    private void stubIoThread(StreamSinkChannel sink) {
        var ioThread = Mockito.mock(XnioIoThread.class);
        Mockito.when(sink.getIoThread()).thenReturn(ioThread);
        Mockito.doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(ioThread).execute(ArgumentMatchers.any(Runnable.class));
    }

    private PatchedChannelImpl<Object> channel(HttpServerExchange exchange, StreamSinkChannel sink) {
        var request = Mockito.mock(RequestImpl.class);
        Mockito.when(request.path()).thenReturn("/api/sessions/events");
        Mockito.when(request.clientIP()).thenReturn("127.0.0.1");
        return new PatchedChannelImpl<>(exchange, sink, new PatchedServerSentEventContextImpl<>(), null, "ref", request);
    }

    private HeaderMap headers(String accept) {
        var headers = new HeaderMap();
        if (accept != null) headers.put(Headers.ACCEPT, accept);
        return headers;
    }
}
