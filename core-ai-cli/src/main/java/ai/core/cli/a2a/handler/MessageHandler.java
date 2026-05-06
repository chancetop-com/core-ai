package ai.core.cli.a2a.handler;

import ai.core.a2a.A2ARunManager;
import ai.core.a2a.A2AHttpPaths;
import ai.core.api.a2a.Message;
import ai.core.api.a2a.SendMessageRequest;
import ai.core.api.a2a.SendMessageResponse;
import ai.core.api.a2a.TaskState;
import ai.core.utils.JsonUtil;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author stephen
 */
public class MessageHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MessageHandler.class);

    private static String extractDecision(Message msg) {
        var text = msg.extractText().trim().toLowerCase(Locale.ROOT);
        if ("approve".equals(text) || "allow".equals(text)) return "approve";
        if ("deny".equals(text) || "reject".equals(text)) return "deny";
        for (var part : msg.parts) {
            if (!(part.data instanceof Map<?, ?> data)) continue;
            var d = data.get("decision");
            if (d != null) return String.valueOf(d);
        }
        return null;
    }

    private static String extractCallId(Message msg) {
        for (var part : msg.parts) {
            if (!(part.data instanceof Map<?, ?> data)) continue;
            var c = data.get("call_id");
            if (c != null) return String.valueOf(c);
            c = data.get("callId");
            if (c != null) return String.valueOf(c);
        }
        return null;
    }

    private static boolean isValidDecision(String decision) {
        return "approve".equalsIgnoreCase(decision) || "deny".equalsIgnoreCase(decision);
    }

    private final A2ARunManager runManager;

    public MessageHandler(A2ARunManager runManager) {
        this.runManager = runManager;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        try {
            var body = readBody(exchange);
            var request = JsonUtil.fromJson(SendMessageRequest.class, body);
            if (!isValidRequest(request)) {
                exchange.setStatusCode(400);
                sendError(exchange, "message.parts required");
                return;
            }

            var accept = exchange.getRequestHeaders().getFirst(Headers.ACCEPT);
            if (exchange.getRelativePath().endsWith(A2AHttpPaths.MESSAGE_STREAM) || accept != null && accept.contains("text/event-stream")) {
                handleStream(exchange, request);
            } else {
                handleSync(exchange, request);
            }
        } catch (IllegalArgumentException e) {
            exchange.setStatusCode(404);
            sendError(exchange, e.getMessage());
        } catch (IllegalStateException e) {
            exchange.setStatusCode(409);
            sendError(exchange, e.getMessage());
        } catch (Exception e) {
            LOGGER.error("error handling message", e);
            exchange.setStatusCode(500);
            sendError(exchange, "internal server error");
        }
    }

    private void handleSync(HttpServerExchange exchange, SendMessageRequest request) {
        if (request.message.taskId != null && !request.message.taskId.isBlank()) {
            var task = continueTask(exchange, request);
            if (task == null) return;
            sendJson(exchange, JsonUtil.toJson(SendMessageResponse.ofTask(task)));
            return;
        }

        if (request.configuration != null && Boolean.TRUE.equals(request.configuration.returnImmediately)) {
            var state = runManager.createStreamingTask(request, null);
            sendJson(exchange, JsonUtil.toJson(SendMessageResponse.ofTask(state.toTask())));
            return;
        }

        var task = runManager.createSyncTask(request);
        sendJson(exchange, JsonUtil.toJson(SendMessageResponse.ofTask(task)));
    }

    private void handleStream(HttpServerExchange exchange, SendMessageRequest request) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/event-stream");
        exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-cache");
        exchange.getResponseHeaders().put(Headers.CONNECTION, "keep-alive");
        exchange.setPersistent(false);

        var channel = exchange.getResponseChannel();
        var completionLatch = new CountDownLatch(1);

        var state = runManager.createStreamingTask(request, sseData -> {
            try {
                var sseFrame = "data: " + sseData + "\n\n";
                channel.write(ByteBuffer.wrap(sseFrame.getBytes(StandardCharsets.UTF_8)));
            } catch (IOException e) {
                LOGGER.debug("SSE write failed", e);
            }
        });

        ai.core.api.server.session.AgentEventListener completionListener = new ai.core.api.server.session.AgentEventListener() {
            @Override
            public void onTurnComplete(ai.core.api.server.session.TurnCompleteEvent event) {
                completionLatch.countDown();
            }

            @Override
            public void onError(ai.core.api.server.session.ErrorEvent event) {
                completionLatch.countDown();
            }
        };
        state.session.onEvent(completionListener);

        try {
            while (true) {
                if (completionLatch.await(1, TimeUnit.SECONDS)) break;
                var taskState = state.getState();
                if (taskState == TaskState.INPUT_REQUIRED || taskState == TaskState.AUTH_REQUIRED) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            state.session.removeEvent(completionListener);
            state.closeStream();
        }
    }

    private ai.core.api.a2a.Task continueTask(HttpServerExchange exchange, SendMessageRequest request) {
        var decision = extractDecision(request.message);
        if (!isValidDecision(decision)) {
            exchange.setStatusCode(400);
            sendError(exchange, "decision must be approve or deny");
            return null;
        }
        runManager.resumeTask(request.message.taskId, decision, extractCallId(request.message));
        return runManager.getTask(request.message.taskId);
    }

    private boolean isValidRequest(SendMessageRequest request) {
        return request != null && request.message != null && request.message.parts != null && !request.message.parts.isEmpty();
    }

    private String readBody(HttpServerExchange exchange) throws IOException {
        exchange.startBlocking();
        return new String(exchange.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/a2a+json");
        exchange.getResponseSender().send(json);
    }

    private void sendError(HttpServerExchange exchange, String message) {
        sendJson(exchange, JsonUtil.toJson(Map.of("error", message != null ? message : "unknown error")));
    }
}
