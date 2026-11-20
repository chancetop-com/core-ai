package ai.core.cli.appserver;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class HostRequestBridgeTest {
    private static <T> T await(AtomicReference<T> reference) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            var value = reference.get();
            if (value != null) return value;
            Thread.sleep(10);
        }
        throw new IllegalStateException("host request was not emitted");
    }

    @Test
    void aToolRequestWaitsForTheHostResponse() throws Exception {
        var seen = new AtomicReference<ObjectNode>();
        NotificationSink sink = (method, params) -> {
            if ("host/request".equals(method)) seen.set((ObjectNode) params);
        };
        var bridge = new HostRequestBridge(() -> sink);
        var payload = Params.object();
        payload.put("watchId", "watch-1");
        var future = CompletableFuture.supplyAsync(() -> bridge.request("watch_session",
                new HostRequestBridge.RequestContext("session-1", "run-1", "turn-1"), payload, 5_000));

        var request = await(seen);
        assertEquals("watch_session", request.path("operation").asText());
        assertEquals("session-1", request.path("sessionId").asText());
        assertEquals("run-1", request.path("taskRunId").asText());
        assertEquals("watch-1", request.path("payload").path("watchId").asText());

        var response = Params.object();
        response.put("requestId", request.path("requestId").asText());
        response.put("ok", true);
        var result = Params.object();
        result.put("status", "active");
        response.set("result", result);
        assertTrue(bridge.respond(response).path("accepted").asBoolean());

        assertEquals("active", future.get(5, TimeUnit.SECONDS).path("status").asText());
    }

    @Test
    void aHostErrorIsSurfacedToTheWaitingTool() throws Exception {
        var seen = new AtomicReference<ObjectNode>();
        NotificationSink sink = (method, params) -> seen.set((ObjectNode) params);
        var bridge = new HostRequestBridge(() -> sink);
        var future = CompletableFuture.supplyAsync(() -> bridge.request("watch_session", null, Params.object(), 5_000));

        var request = await(seen);
        var response = Params.object();
        response.put("requestId", request.path("requestId").asText());
        response.put("ok", false);
        var error = Params.object();
        error.put("code", "NO_ORIGIN");
        error.put("message", "no origin");
        response.set("error", error);
        assertTrue(bridge.respond(response).path("accepted").asBoolean());

        var thrown = future.handle((value, failure) -> failure).get(5, TimeUnit.SECONDS);
        assertTrue(thrown instanceof java.util.concurrent.CompletionException);
        var cause = thrown.getCause();
        assertTrue(cause instanceof RpcException);
        assertEquals("no origin", cause.getMessage());
    }

    @Test
    void respondingTwiceIsRejectedInsteadOfCompletingAnotherRequest() {
        var bridge = new HostRequestBridge(() -> (method, params) -> { });
        var response = Params.object();
        response.put("requestId", "missing");
        response.put("ok", true);
        assertFalse(bridge.respond(response).path("accepted").asBoolean());
    }
}
