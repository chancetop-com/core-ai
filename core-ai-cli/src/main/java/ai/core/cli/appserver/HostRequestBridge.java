package ai.core.cli.appserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
/**
 * A tool-side request to the Desktop host over the trusted stdio channel. The engine never blocks
 * its JSON-RPC dispatch thread: the tool waits on its own worker and the host responds with a
 * {@code host/respond} request that only completes the pending future.
 *
 * @author stephen
 */
final class HostRequestBridge {
    static final long DEFAULT_TIMEOUT_MILLIS = 10_000L;

    private final Supplier<NotificationSink> sinkSupplier;
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;

    HostRequestBridge(Supplier<NotificationSink> sinkSupplier) {
        this.sinkSupplier = sinkSupplier;
    }

    JsonNode request(String operation, RequestContext context, ObjectNode payload, long timeoutMillis) {
        if (closed) throw new IllegalStateException("host request bridge is closed");
        var sink = sinkSupplier.get();
        if (sink == null) throw new IllegalStateException("desktop host is not attached");
        var requestId = "host-" + UUID.randomUUID();
        var params = Params.object();
        params.put("requestId", requestId);
        params.put("operation", operation);
        if (context != null) {
            if (context.sessionId() != null) params.put("sessionId", context.sessionId());
            if (context.taskRunId() != null) params.put("taskRunId", context.taskRunId());
            if (context.executionId() != null) params.put("executionId", context.executionId());
        }
        params.set("payload", payload != null ? payload : Params.object());
        var future = new CompletableFuture<JsonNode>();
        pending.put(requestId, future);
        sink.notify("host/request", params);
        try {
            return future.get(timeoutMillis > 0 ? timeoutMillis : DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(requestId);
            throw new IllegalStateException("host request timed out: " + operation, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(requestId);
            throw new IllegalStateException("host request interrupted: " + operation, e);
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof RpcException rpc && rpc.data() != null) {
                throw new RpcException(rpc.code(), rpc.getMessage(), rpc.data(), e);
            }
            if (cause instanceof RuntimeException runtime) {
                throw new IllegalStateException(runtime.getMessage(), e);
            }
            throw new IllegalStateException("host request failed", e);
        }
    }

    ObjectNode respond(ObjectNode params) {
        var requestId = Params.requiredText(params, "requestId");
        var future = pending.remove(requestId);
        var node = Params.object();
        if (future == null) {
            node.put("accepted", false);
            node.put("error", "unknown or already completed host request");
            return node;
        }
        if (params.path("ok").asBoolean(false)) {
            future.complete(params.path("result"));
        } else {
            var error = params.path("error");
            var message = error.path("message").asText("host request failed");
            future.completeExceptionally(new RpcException(RpcException.INTERNAL_ERROR, message, error.isObject() ? error : null));
        }
        node.put("accepted", true);
        return node;
    }

    void close() {
        closed = true;
        pending.values().forEach(future -> future.completeExceptionally(new IllegalStateException("host request bridge closed")));
        pending.clear();
    }

    record RequestContext(String sessionId, String taskRunId, String executionId) {
    }
}
