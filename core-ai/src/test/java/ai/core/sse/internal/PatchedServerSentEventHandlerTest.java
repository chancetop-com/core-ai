package ai.core.sse.internal;

import core.framework.http.HTTPMethod;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        handler.add(HTTPMethod.POST, "/api/litellm/v1/chat/completions", Object.class, (request, channel, lastEventId) -> {
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
        assertTrue(handler.check(POST, "/api/litellm/v1/chat/completions", headers("application/json")));
        assertTrue(handler.check(POST, "/api/litellm/v1/chat/completions", headers(null)));
    }

    @Test
    void unknownRouteIsRejected() {
        assertFalse(handler.check(POST, "/api/unknown", headers("text/event-stream")));
    }

    private HeaderMap headers(String accept) {
        var headers = new HeaderMap();
        if (accept != null) headers.put(Headers.ACCEPT, accept);
        return headers;
    }
}
