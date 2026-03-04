package ai.core.api.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class ErrorEvent implements AgentEvent {
    public static ErrorEvent of(String sessionId, String message, String detail) {
        var event = new ErrorEvent();
        event.sessionId = sessionId;
        event.message = message;
        event.detail = detail;
        return event;
    }

    @NotNull
    @Property(name = "sessionId")
    public String sessionId;

    @NotNull
    @Property(name = "message")
    public String message;

    @Property(name = "detail")
    public String detail;

    @Override
    public String sessionId() {
        return sessionId;
    }
}
