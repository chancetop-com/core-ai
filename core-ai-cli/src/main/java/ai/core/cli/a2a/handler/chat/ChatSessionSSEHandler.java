package ai.core.cli.a2a.handler.chat;

import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.PlanUpdateEvent;
import ai.core.api.server.session.ReasoningChunkEvent;
import ai.core.api.server.session.ReasoningCompleteEvent;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.EnvironmentOutputChunkEvent;
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
import java.time.ZonedDateTime;
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
        // Build the event flattening data fields into the top level for front-end compatibility
        var event = new java.util.LinkedHashMap<String, Object>();
        event.put("type", eventType);
        event.put("sessionId", sessionId);
        event.put("timestamp", ZonedDateTime.now().toString());
        // Flatten data fields into top level
        if (data != null) {
            event.putAll(data);
        }
        return "data: " + JsonUtil.toJson(event) + "\n\n";
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
        chatSession.setSseHandlerThread(Thread.currentThread());
        exchange.addExchangeCompleteListener((ex, next) -> {
            chatSession.signalSseClose();
            chatSession.interruptSseHandler();
            chatSession.removeListener(listener);
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
            String frame = pollEvent(eventQueue);
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

    private String pollEvent(BlockingQueue<String> eventQueue) {
        try {
            return eventQueue.poll(5, TimeUnit.SECONDS);
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
            enqueueSseEvent("text_chunk", toMap(
                "content", event.chunk,
                "is_final_chunk", false
            ));
        }

        @Override
        public void onReasoningChunk(ReasoningChunkEvent event) {
            enqueueSseEvent("reasoning_chunk", toMap(
                "content", event.chunk,
                "is_final_chunk", false
            ));
        }

        @Override
        public void onReasoningComplete(ReasoningCompleteEvent event) {
            // Not used by frontend
        }

        @Override
        public void onToolStart(ToolStartEvent event) {
            enqueueSseEvent("tool_start", toMap(
                "call_id", event.callId,
                "tool_name", event.toolName,
                "tool_args", event.arguments != null ? Map.of("raw", event.arguments) : null,
                "task_id", event.taskId,
                "run_in_background", event.runInBackground
            ));
        }

        @Override
        public void onToolResult(ToolResultEvent event) {
            enqueueSseEvent("tool_result", toMap(
                "call_id", event.callId,
                "tool_name", event.toolName,
                "status", event.status,
                "result", nullToEmpty(event.result)
            ));
        }

        @Override
        public void onEnvironmentOutput(EnvironmentOutputChunkEvent event) {
            enqueueSseEvent("environment_output_chunk", toMap(
                "source", event.source,
                "call_id", event.callId,
                "chunk", event.chunk
            ));
        }

        @Override
        public void onToolApprovalRequest(ToolApprovalRequestEvent event) {
            enqueueSseEvent("tool_approval_request", toMap(
                "call_id", event.callId,
                "tool_name", event.toolName,
                "arguments", nullToEmpty(event.arguments)
            ));
        }

        @Override
        public void onTurnComplete(TurnCompleteEvent event) {
            LOGGER.info("[SSE] onTurnComplete: sessionId={}, cancelled={}, output={}",
                sessionId, event.cancelled, event.output != null ? event.output.substring(0, Math.min(50, event.output.length())) : "null");
            Map<String, Object> data = new HashMap<>();
            if (event.output != null) {
                data.put("output", event.output);
            }
            if (Boolean.TRUE.equals(event.cancelled)) {
                data.put("cancelled", true);
            }
            if (event.maxTurnsReached != null && event.maxTurnsReached) {
                data.put("max_turns_reached", true);
            }
            if (event.inputTokens != null || event.outputTokens != null) {
                data.put("input_tokens", event.inputTokens);
                data.put("output_tokens", event.outputTokens);
            }
            enqueueSseEvent("turn_complete", data);
            // Close SSE connection after turn complete to ensure all tool events are sent
            // This is the proper time to close - after all events for this turn have been dispatched
            close();
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
            String frame = buildSseEvent(eventType, data, sessionId);
            try {
                // Use non-blocking offer to avoid blocking the agent thread
                // If queue is full, drop the event and log a warning
                // This prevents slow SSE clients from blocking tool execution
                if (!eventQueue.offer(frame, 100, TimeUnit.MILLISECONDS)) {
                    LOGGER.warn("[SSE] event queue full, dropping event: {} for session {}", eventType, sessionId);
                }
            } catch (InterruptedException e) {
                // Restore interrupt flag - we still want to send the event even during cancel
                Thread.currentThread().interrupt();
                // Try non-blocking offer as last resort
                if (!eventQueue.offer(frame)) {
                    LOGGER.warn("[SSE] event queue full, dropping event after interrupt: {} for session {}", eventType, sessionId);
                } else {
                    LOGGER.debug("[SSE] event sent via non-blocking offer after interrupt: {} for session {}", eventType, sessionId);
                }
            }
        }

        void close() {
            try {
                eventQueue.offer(SSE_CLOSE_MARKER, 5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Try non-blocking offer as last resort
                if (!eventQueue.offer(SSE_CLOSE_MARKER)) {
                    LOGGER.debug("[SSE] close marker dropped for session {}", sessionId);
                }
            }
        }

        private String nullToEmpty(String value) {
            return value != null ? value : "";
        }
    }
}
