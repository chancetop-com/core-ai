package ai.core.api.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class ToolApprovalRequestEvent implements AgentEvent {
    public static ToolApprovalRequestEvent of(String sessionId, String callId, String toolName, String arguments) {
        var event = new ToolApprovalRequestEvent();
        event.sessionId = sessionId;
        event.callId = callId;
        event.toolName = toolName;
        event.arguments = arguments;
        return event;
    }

    @NotNull
    @Property(name = "sessionId")
    public String sessionId;

    @NotNull
    @Property(name = "callId")
    public String callId;

    @NotNull
    @Property(name = "toolName")
    public String toolName;

    @Property(name = "arguments")
    public String arguments;

    @Override
    public String sessionId() {
        return sessionId;
    }
}
