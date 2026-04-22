package ai.core.api.server.session;

import java.util.Map;

/**
 * @author stephen
 */
public interface AgentSession {
    String id();

    void sendMessage(String message);

    void sendMessage(String message, Map<String, Object> variables);

    void onEvent(AgentEventListener listener);

    void approveToolCall(String callId, ApprovalDecision decision);

    void cancelTurn();

    void close();
}
