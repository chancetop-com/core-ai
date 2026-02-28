package ai.core.api.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class ToolResultEvent implements AgentEvent {
    @Override
    public String sessionId() {
        return sessionId;
    }

    public static ToolResultEvent of(String sessionId, String callId, String toolName, String status, String result) {
        var event = new ToolResultEvent();
        event.sessionId = sessionId;
        event.callId = callId;
        event.toolName = toolName;
        event.status = status;
        event.result = result;
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

    @NotNull
    @Property(name = "status")
    public String status;

    @Property(name = "result")
    public String result;
}
