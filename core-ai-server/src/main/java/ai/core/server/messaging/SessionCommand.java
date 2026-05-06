package ai.core.server.messaging;

import ai.core.api.server.session.ApprovalDecision;
import ai.core.utils.JsonUtil;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * @author stephen
 */
public record SessionCommand(CommandType type, String sessionId, String userId, String payload, String requestId) {
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
        var requestId = map.get("requestId");
        return new SessionCommand(type, sessionId, userId, payload, requestId);
    }

    // --- Existing command factories (no requestId) ---

    public static SessionCommand sendMessage(String sessionId, String userId, String message, Map<String, Object> variables) {
        var payload = JsonUtil.toJson(Map.of(
                "message", message,
                "variables", variables != null ? variables : Map.of()
        ));
        return new SessionCommand(CommandType.SEND_MESSAGE, sessionId, userId, payload, null);
    }

    public static SessionCommand approveToolCall(String sessionId, String userId, String callId, ApprovalDecision decision) {
        var payload = JsonUtil.toJson(Map.of(
                "callId", callId,
                "decision", decision.name()
        ));
        return new SessionCommand(CommandType.APPROVE_TOOL, sessionId, userId, payload, null);
    }

    public static SessionCommand cancelTurn(String sessionId, String userId) {
        return new SessionCommand(CommandType.CANCEL_TURN, sessionId, userId, "{}", null);
    }

    public static SessionCommand closeSession(String sessionId, String userId) {
        return new SessionCommand(CommandType.CLOSE_SESSION, sessionId, userId, "{}", null);
    }

    // --- RPC command factories (with requestId) ---

    public static SessionCommand loadTools(String sessionId, String userId, String toolsJson, String requestId) {
        return new SessionCommand(CommandType.LOAD_TOOLS, sessionId, userId, toolsJson, requestId);
    }

    public static SessionCommand loadSkills(String sessionId, String userId, String skillIdsJson, String requestId) {
        return new SessionCommand(CommandType.LOAD_SKILLS, sessionId, userId, skillIdsJson, requestId);
    }

    public static SessionCommand unloadSkills(String sessionId, String userId, String skillIdsJson, String requestId) {
        return new SessionCommand(CommandType.UNLOAD_SKILLS, sessionId, userId, skillIdsJson, requestId);
    }

    public static SessionCommand loadSubAgents(String sessionId, String userId, String agentIdsJson, String requestId) {
        return new SessionCommand(CommandType.LOAD_SUB_AGENTS, sessionId, userId, agentIdsJson, requestId);
    }

    public static SessionCommand generateAgentDraft(String sessionId, String userId, String requestId) {
        return new SessionCommand(CommandType.GENERATE_AGENT_DRAFT, sessionId, userId, "{}", requestId);
    }

    public static SessionCommand a2aCancelTask(String sessionId, String userId, String taskId, String requestId) {
        var payload = JsonUtil.toJson(Map.of("taskId", taskId));
        return new SessionCommand(CommandType.A2A_CANCEL_TASK, sessionId, userId, payload, requestId);
    }

    public static SessionCommand a2aStartTask(String sessionId, String userId, String payloadJson, String requestId) {
        return new SessionCommand(CommandType.A2A_START_TASK, sessionId, userId, payloadJson, requestId);
    }

    public static SessionCommand a2aResumeTask(String sessionId, String userId, String messageJson, String requestId) {
        return new SessionCommand(CommandType.A2A_RESUME_TASK, sessionId, userId, messageJson, requestId);
    }

    public Map<String, String> toStreamMap() {
        var map = new HashMap<String, String>();
        map.put("sessionId", sessionId);
        map.put("userId", userId);
        map.put("type", type.name());
        map.put("payload", payload);
        map.put("timestamp", Instant.now().toString());
        if (requestId != null) {
            map.put("requestId", requestId);
        }
        return map;
    }
}
