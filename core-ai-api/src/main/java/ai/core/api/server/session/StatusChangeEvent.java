package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class StatusChangeEvent implements AgentEvent {
    public static StatusChangeEvent of(String sessionId, SessionStatus status) {
        var event = new StatusChangeEvent();
        event.sessionId = sessionId;
        event.status = status;
        return event;
    }

    @NotNull
    @Property(name = "sessionId")
    public String sessionId;

    @NotNull
    @Property(name = "status")
    public SessionStatus status;

    @Override
    public String sessionId() {
        return sessionId;
    }
}
