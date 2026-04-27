package ai.core.server.messaging;

import ai.core.api.server.session.ApprovalDecision;
import ai.core.utils.JsonUtil;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * @author stephen
 */
public record SessionCommand(CommandType type, String sessionId, String userId, String payload) {
    public static final String POD_STREAM_PREFIX = "coreai:commands:pod:";
    public static final String UNOWNED_STREAM = "coreai:commands:unowned";
    public static final String UNOWNED_CONSUMER_GROUP = "cg:unowned";

    static String podStreamKey(String hostname) {
        return POD_STREAM_PREFIX + hostname;
    }

    public static SessionCommand fromMap(Map<String, String> map) {
        var type = CommandType.valueOf(map.get("type"));
        var sessionId = map.get("sessionId");
        var userId = map.get("userId");
        var payload = map.get("payload");
        return new SessionCommand(type, sessionId, userId, payload);
    }

    public static SessionCommand sendMessage(String sessionId, String userId, String message, Map<String, Object> variables) {
        var payload = JsonUtil.toJson(Map.of(
                "message", message,
                "variables", variables != null ? variables : Map.of()
        ));
        return new SessionCommand(CommandType.SEND_MESSAGE, sessionId, userId, payload);
    }

    public static SessionCommand approveToolCall(String sessionId, String userId, String callId, ApprovalDecision decision) {
        var payload = JsonUtil.toJson(Map.of(
                "callId", callId,
                "decision", decision.name()
        ));
        return new SessionCommand(CommandType.APPROVE_TOOL, sessionId, userId, payload);
    }

    public static SessionCommand cancelTurn(String sessionId, String userId) {
        return new SessionCommand(CommandType.CANCEL_TURN, sessionId, userId, "{}");
    }

    public static SessionCommand closeSession(String sessionId, String userId) {
        return new SessionCommand(CommandType.CLOSE_SESSION, sessionId, userId, "{}");
    }

    public Map<String, String> toStreamMap() {
        var map = new HashMap<String, String>();
        map.put("sessionId", sessionId);
        map.put("userId", userId);
        map.put("type", type.name());
        map.put("payload", payload);
        map.put("timestamp", Instant.now().toString());
        return map;
    }
}
