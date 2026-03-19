package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * author: lim chen
 * date: 2026/3/19
 * description:
 */
public class OnToolEvent implements AgentEvent {

    public static OnToolEvent of(String sessionId, String toolName, String arguments) {
        var event = new OnToolEvent();
        event.sessionId = sessionId;
        event.toolName = toolName;
        event.arguments = arguments;
        return event;
    }

    @NotNull
    @Property(name = "sessionId")
    public String sessionId;
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
