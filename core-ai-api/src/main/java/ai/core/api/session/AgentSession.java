package ai.core.api.session;

/**
 * @author stephen
 */
public interface AgentSession {
    String id();

    void sendMessage(String message);

    void onEvent(AgentEventListener listener);

    void approveToolCall(String callId, ApprovalDecision decision);

    void close();
}
