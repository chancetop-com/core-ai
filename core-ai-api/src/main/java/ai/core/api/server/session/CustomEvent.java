package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class CustomEvent implements AgentEvent {
    public static CustomEvent of(String sessionId, String name, String data, String callId) {
        var event = new CustomEvent();
        event.sessionId = sessionId;
        event.name = name;
        event.data = data;
        event.callId = callId;
        return event;
    }

    @NotNull
    @Property(name = "sessionId")
    public String sessionId;

    @NotNull
    @Property(name = "name")
    public String name;

    @NotNull
    @Property(name = "data")
    public String data;

    @Property(name = "callId")
    public String callId;

    @Override
    public String sessionId() {
        return sessionId;
    }
}
