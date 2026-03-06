package ai.core.cli.remote;

import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.AgentSession;
import ai.core.api.server.session.ApprovalDecision;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.EventType;
import ai.core.api.server.session.ReasoningChunkEvent;
import ai.core.api.server.session.ReasoningCompleteEvent;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.cli.DebugLog;
import ai.core.utils.JsonUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author stephen
 */
public class HttpAgentSession implements AgentSession {
    private final String sessionId;
    private final RemoteApiClient api;
    private final List<AgentEventListener> listeners = new CopyOnWriteArrayList<>();
    private volatile Thread sseThread;
    private final CountDownLatch sseConnected = new CountDownLatch(1);

    private HttpAgentSession(String sessionId, RemoteApiClient api) {
        this.sessionId = sessionId;
        this.api = api;
    }

    public static HttpAgentSession connect(RemoteApiClient api, String agentId) {
        var sessionId = createSession(api, agentId);
        var session = new HttpAgentSession(sessionId, api);
        session.connectSse();
        try {
            if (!session.sseConnected.await(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("SSE connection timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while waiting for SSE connection", e);
        }
        return session;
    }

    @SuppressWarnings("unchecked")
    private static String createSession(RemoteApiClient api, String agentId) {
        var bodyMap = new java.util.LinkedHashMap<String, Object>();
        if (agentId != null) bodyMap.put("agent_id", agentId);
        var json = api.post("/api/sessions", bodyMap);
        if (json == null) throw new RuntimeException("failed to create session");
        Map<String, Object> result = JsonUtil.fromJson(Map.class, json);
        return (String) result.get("sessionId");
    }

    private void connectSse() {
        sseThread = new Thread(() -> {
            try {
                var request = api.putRequest("/api/sessions/events?sessionId=" + sessionId)
                        .header("Accept", "text/event-stream")
                        .method("PUT", HttpRequest.BodyPublishers.noBody())
                        .build();

                var response = api.httpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
                DebugLog.log("SSE connected, status=" + response.statusCode());
                sseConnected.countDown();

                try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data:")) {
                            var data = line.substring(5).trim();
                            if (!data.isEmpty()) {
                                handleSseData(data);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    DebugLog.log("SSE connection error: " + e.getMessage());
                }
            }
        }, "sse-reader");
        sseThread.setDaemon(true);
        sseThread.start();
    }

    @SuppressWarnings("unchecked")
    private void handleSseData(String json) {
        try {
            Map<String, Object> envelope = JsonUtil.fromJson(Map.class, json);
            var typeStr = (String) envelope.get("type");
            var dataJson = (String) envelope.get("data");
            if (typeStr == null || dataJson == null) return;

            var type = parseEventType(typeStr);
            if (type == null) {
                DebugLog.log("unknown SSE event type: " + typeStr);
                return;
            }

            AgentEvent event = deserializeEvent(type, dataJson);
            if (event != null) {
                dispatch(event);
            }
        } catch (Exception e) {
            DebugLog.log("failed to parse SSE event: " + e.getMessage());
        }
    }

    private EventType parseEventType(String typeStr) {
        return switch (typeStr) {
            case "text_chunk" -> EventType.TEXT_CHUNK;
            case "reasoning_chunk" -> EventType.REASONING_CHUNK;
            case "reasoning_complete" -> EventType.REASONING_COMPLETE;
            case "tool_start" -> EventType.TOOL_START;
            case "tool_result" -> EventType.TOOL_RESULT;
            case "tool_approval_request" -> EventType.TOOL_APPROVAL_REQUEST;
            case "turn_complete" -> EventType.TURN_COMPLETE;
            case "error" -> EventType.ERROR;
            case "status_change" -> EventType.STATUS_CHANGE;
            default -> null;
        };
    }

    private AgentEvent deserializeEvent(EventType type, String dataJson) {
        return switch (type) {
            case TEXT_CHUNK -> JsonUtil.fromJson(TextChunkEvent.class, dataJson);
            case REASONING_CHUNK -> JsonUtil.fromJson(ReasoningChunkEvent.class, dataJson);
            case REASONING_COMPLETE -> JsonUtil.fromJson(ReasoningCompleteEvent.class, dataJson);
            case TOOL_START -> JsonUtil.fromJson(ToolStartEvent.class, dataJson);
            case TOOL_RESULT -> JsonUtil.fromJson(ToolResultEvent.class, dataJson);
            case TOOL_APPROVAL_REQUEST -> JsonUtil.fromJson(ToolApprovalRequestEvent.class, dataJson);
            case TURN_COMPLETE -> JsonUtil.fromJson(TurnCompleteEvent.class, dataJson);
            case ERROR -> JsonUtil.fromJson(ErrorEvent.class, dataJson);
            case STATUS_CHANGE -> JsonUtil.fromJson(StatusChangeEvent.class, dataJson);
        };
    }

    private void dispatch(AgentEvent event) {
        for (var listener : listeners) {
            try {
                if (event instanceof TextChunkEvent e) listener.onTextChunk(e);
                else if (event instanceof ReasoningChunkEvent e) listener.onReasoningChunk(e);
                else if (event instanceof ReasoningCompleteEvent e) listener.onReasoningComplete(e);
                else if (event instanceof ToolStartEvent e) listener.onToolStart(e);
                else if (event instanceof ToolResultEvent e) listener.onToolResult(e);
                else if (event instanceof ToolApprovalRequestEvent e) listener.onToolApprovalRequest(e);
                else if (event instanceof TurnCompleteEvent e) listener.onTurnComplete(e);
                else if (event instanceof ErrorEvent e) listener.onError(e);
                else if (event instanceof StatusChangeEvent e) listener.onStatusChange(e);
            } catch (Exception e) {
                DebugLog.log("listener error: " + e.getMessage());
            }
        }
    }

    @Override
    public String id() {
        return sessionId;
    }

    @Override
    public void sendMessage(String message) {
        api.post("/api/sessions/" + sessionId + "/messages", Map.of("message", message));
    }

    @Override
    public void onEvent(AgentEventListener listener) {
        listeners.add(listener);
    }

    @Override
    public void approveToolCall(String callId, ApprovalDecision decision) {
        api.post("/api/sessions/" + sessionId + "/approve", Map.of("callId", callId, "decision", decision.name()));
    }

    @Override
    public void cancelTurn() {
        api.postEmpty("/api/sessions/" + sessionId + "/cancel");
    }

    @Override
    public void close() {
        if (sseThread != null) {
            sseThread.interrupt();
        }
        api.delete("/api/sessions/" + sessionId);
    }
}
