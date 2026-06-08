package ai.core.server.channel;

import java.util.Map;

/**
 * @author stephen
 */
public class InboundEvent {
    public String channelType;
    public String channelUserId;
    public String conversationId;
    public String messageText;
    public String threadId;

    /** "message" (default) or "tool_decision" */
    public String commandType;

    /** Tool call ID — only set when commandType is "tool_decision" */
    public String toolCallId;

    /** "approve" or "deny" — only set when commandType is "tool_decision" */
    public String toolDecision;

    public Map<String, String> metadata;
}
