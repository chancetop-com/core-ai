package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class ToolApprovalRequestEvent implements AgentEvent {
    public static ToolApprovalRequestEvent of(String sessionId, String callId, String toolName, String arguments, String suggestedPattern) {
        var event = new ToolApprovalRequestEvent();
        event.sessionId = sessionId;
        event.callId = callId;
        event.toolName = toolName;
        event.arguments = arguments;
        event.suggestedPattern = suggestedPattern;
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

    @Property(name = "suggestedPattern")
    public String suggestedPattern;

    @Override
    public String sessionId() {
        return sessionId;
    }
}
