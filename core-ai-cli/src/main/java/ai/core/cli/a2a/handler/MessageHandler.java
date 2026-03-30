package ai.core.cli.a2a.handler;

import ai.core.a2a.A2ARunManager;
import ai.core.api.a2a.SendMessageRequest;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author stephen
 */
public class MessageHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MessageHandler.class);

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

            var accept = exchange.getRequestHeaders().getFirst(Headers.ACCEPT);
            if (accept != null && accept.contains("text/event-stream")) {
                handleStream(exchange, request);
            } else {
                handleSync(exchange, request);
            }
        } catch (Exception e) {
            LOGGER.error("error handling message", e);
            exchange.setStatusCode(500);
            sendJson(exchange, "{\"error\":\"internal server error\"}");
        }
    }

    private void handleSync(HttpServerExchange exchange, SendMessageRequest request) {
        var task = runManager.createSyncTask(request);
        sendJson(exchange, JsonUtil.toJson(task));
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

        state.session.onEvent(new ai.core.api.server.session.AgentEventListener() {
            @Override
            public void onTurnComplete(ai.core.api.server.session.TurnCompleteEvent event) {
                completionLatch.countDown();
            }

            @Override
            public void onError(ai.core.api.server.session.ErrorEvent event) {
                completionLatch.countDown();
            }
        });

        try {
            while (!completionLatch.await(1, TimeUnit.SECONDS)) {
                var taskState = state.getState();
                if (taskState == TaskState.COMPLETED || taskState == TaskState.FAILED || taskState == TaskState.CANCELED) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String readBody(HttpServerExchange exchange) throws IOException {
        exchange.startBlocking();
        return new String(exchange.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(json);
    }
}
