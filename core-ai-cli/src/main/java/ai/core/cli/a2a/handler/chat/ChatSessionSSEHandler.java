package ai.core.cli.a2a.handler.chat;

import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.PlanUpdateEvent;
import ai.core.api.server.session.ReasoningChunkEvent;
import ai.core.api.server.session.ReasoningCompleteEvent;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.cli.session.LocalChatSessionManager;
import ai.core.cli.session.LocalChatSessionManager.ChatSession;
import ai.core.utils.JsonUtil;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.io.IoCallback;
import io.undertow.io.Sender;
import io.undertow.util.StatusCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author stephen
 */
public class ChatSessionSSEHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatSessionSSEHandler.class);
    private static final String SSE_CLOSE_MARKER = "__SSE_CLOSE__";

    static String buildSseEvent(String eventType, Map<String, Object> data, String sessionId) {
        Map<String, Object> payload = Map.of(
            "type", eventType,
            "sessionId", sessionId,
            "timestamp", java.time.ZonedDateTime.now().toString(),
            "data", JsonUtil.toJson(data)
        );
        return "data: " + JsonUtil.toJson(payload) + "\n\n";
    }

    static Map<String, Object> toMap(Object... keyValuePairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }

    private final LocalChatSessionManager sessionManager;

    public ChatSessionSSEHandler(LocalChatSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        String sessionId = exchange.getQueryParameters().get("agent-session-id").getFirst();
        if (sessionId == null) {
            sendError(exchange, 400, "agent-session-id query parameter required");
            return;
        }

        var chatSession = sessionManager.getSession(sessionId);
        if (chatSession == null) {
            LOGGER.warn("[SSE] session not found: {}", sessionId);
            sendError(exchange, 404, "session not found");
            return;
        }

        try {
            streamSseEvents(exchange, chatSession, sessionId);
        } catch (InterruptedException e) {
            LOGGER.debug("[SSE] SSE handler interrupted, sessionId={}", sessionId);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.error("failed to setup SSE stream, sessionId={}", sessionId, e);
            if (!exchange.isResponseStarted()) {
                sendError(exchange, 500, "failed to setup SSE stream");
            }
        }
    }

    private void sendError(HttpServerExchange exchange, int status, String message) {
        exchange.setStatusCode(status);
        sendJson(exchange, "{\"error\":\""
            + message
            + "\"}");
    }

    private void streamSseEvents(HttpServerExchange exchange, ChatSession chatSession, String sessionId)
            throws InterruptedException {
        setupSseResponse(exchange);
        BlockingQueue<String> eventQueue = new LinkedBlockingQueue<>();
        var listener = new SseListener(sessionId, eventQueue);

        chatSession.addListener(listener);
        exchange.addExchangeCompleteListener((ex, next) -> {
            chatSession.removeListener(listener);
            listener.close();
            chatSession.signalSseClose();
            next.proceed();
        });

        sendSync(exchange, buildSseEvent("connected", Map.of("status", "connected"), sessionId));
        LOGGER.debug("SSE connection established, sessionId={}", sessionId);

        drainEventQueue(exchange, eventQueue, sessionId);
        LOGGER.debug("SSE connection closed, sessionId={}", sessionId);
    }

    private void setupSseResponse(HttpServerExchange exchange) {
        exchange.setStatusCode(StatusCodes.OK);
        var headers = exchange.getResponseHeaders();
        headers.put(Headers.CONTENT_TYPE, "text/event-stream");
        headers.put(Headers.CACHE_CONTROL, "no-cache");
        headers.put(Headers.CONNECTION, "keep-alive");
        headers.put(Headers.TRANSFER_ENCODING, "chunked");
    }

    private void drainEventQueue(HttpServerExchange exchange, BlockingQueue<String> eventQueue, String sessionId) {
        while (true) {
            String frame = pollEvent(eventQueue, sessionId);
            if (frame == null) {
                continue;
            }
            if (SSE_CLOSE_MARKER.equals(frame)) {
                break;
            }
            if (!sendFrame(exchange, frame, sessionId)) {
                break;
            }
        }
    }

    private String pollEvent(BlockingQueue<String> eventQueue, String sessionId) {
        try {
            String frame = eventQueue.poll(30, TimeUnit.SECONDS);
            if (frame == null) {
                LOGGER.warn("[SSE] poll timeout, sessionId={}", sessionId);
                return null;
            }
            return frame;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SSE_CLOSE_MARKER;
        }
    }

    private boolean sendFrame(HttpServerExchange exchange, String frame, String sessionId) {
        try {
            sendSync(exchange, frame);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            LOGGER.warn("[SSE] send failed, sessionId={}", sessionId, e);
            return false;
        }
    }

    private void sendSync(HttpServerExchange exchange, String data) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        exchange.getResponseSender().send(data, new IoCallback() {
            @Override
            public void onComplete(HttpServerExchange exchange, Sender sender) {
                latch.countDown();
            }

            @Override
            public void onException(HttpServerExchange exchange, Sender sender, IOException exception) {
                error.set(exception);
                latch.countDown();
            }
        });

        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new RuntimeException("sendSync timeout for sessionId="
                + exchange.getQueryParameters().get("agent-session-id").getFirst());
        }
        Throwable ex = error.get();
        if (ex != null) {
            throw new RuntimeException("sendSync failed", ex);
        }
    }

    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(json);
    }

    public static class SseListener implements AgentEventListener {
        private final String sessionId;
        private final BlockingQueue<String> eventQueue;

        public SseListener(String sessionId, BlockingQueue<String> eventQueue) {
            this.sessionId = sessionId;
            this.eventQueue = eventQueue;
        }

        @Override
        public void onTextChunk(TextChunkEvent event) {
            logChunk("onTextChunk", event.chunk);
            enqueueSseEvent("text_chunk", toMap(
                "text", event.chunk,
                "is_final_chunk", false
            ));
        }

        @Override
        public void onReasoningChunk(ReasoningChunkEvent event) {
            logChunk("onReasoningChunk", event.chunk);
            enqueueSseEvent("reasoning_chunk", toMap("text", event.chunk));
        }

        @Override
        public void onReasoningComplete(ReasoningCompleteEvent event) {
            // Not used by frontend
        }

        @Override
        public void onToolStart(ToolStartEvent event) {
            enqueueSseEvent("tool_start", toMap(
                "callId", event.callId,
                "name", event.toolName,
                "arguments", nullToEmpty(event.arguments)
            ));
        }

        @Override
        public void onToolResult(ToolResultEvent event) {
            enqueueSseEvent("tool_result", toMap(
                "callId", event.callId,
                "name", event.toolName,
                "status", event.status,
                "result", nullToEmpty(event.result)
            ));
        }

        @Override
        public void onToolApprovalRequest(ToolApprovalRequestEvent event) {
            enqueueSseEvent("tool_approval_request", toMap(
                "callId", event.callId,
                "name", event.toolName,
                "arguments", nullToEmpty(event.arguments)
            ));
        }

        @Override
        public void onTurnComplete(TurnCompleteEvent event) {
            Map<String, Object> data = new HashMap<>();
            if (event.output != null) {
                data.put("output", event.output);
            }
            if (event.inputTokens != null || event.outputTokens != null) {
                data.put("inputTokens", event.inputTokens);
                data.put("outputTokens", event.outputTokens);
            }
            enqueueSseEvent("turn_complete", data);
        }

        @Override
        public void onError(ErrorEvent event) {
            LOGGER.debug("[SSE] onError: sessionId={}, message={}", sessionId, event.message);
            enqueueSseEvent("error", toMap("message", event.message != null ? event.message : "Unknown error"));
        }

        @Override
        public void onStatusChange(StatusChangeEvent event) {
            // Not used by frontend
        }

        @Override
        public void onPlanUpdate(PlanUpdateEvent event) {
            var todos = event.todos.stream()
                .map(t -> toMap("content", t.content, "status", t.status))
                .toList();
            enqueueSseEvent("plan_update", toMap("todos", todos));
        }

        void enqueueSseEvent(String eventType, Map<String, Object> data) {
            try {
                String frame = buildSseEvent(eventType, data, sessionId);
                boolean offered = eventQueue.offer(frame, 10, TimeUnit.SECONDS);
                LOGGER.warn("[SSE] enqueued event: type={}, offered={}, queueSize={}, sessionId={}",
                    eventType, offered, eventQueue.size(), sessionId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("[SSE] enqueue interrupted for session {}", sessionId);
            }
        }

        void close() {
            try {
                eventQueue.offer(SSE_CLOSE_MARKER, 5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void logChunk(String method, String chunk) {
            LOGGER.warn("[SSE] {}: sessionId={}, chunk={}", sessionId, method,
                chunk != null && chunk.length() > 50 ? chunk.substring(0, 50) + "..." : chunk);
        }

        private String nullToEmpty(String value) {
            return value != null ? value : "";
        }
    }
}
