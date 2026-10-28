package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class ErrorEvent implements AgentEvent {
    public static ErrorEvent of(String sessionId, String message, String detail) {
        return of(sessionId, message, detail, null);
    }

    public static ErrorEvent of(String sessionId, String message, String detail, String turnId) {
        var event = new ErrorEvent();
        event.sessionId = sessionId;
        event.message = message;
        event.detail = detail;
        event.turnId = turnId;
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

    /** Correlation id of the turn that failed; null for older clients. */
    @Property(name = "turn_id")
    public String turnId;

    @Override
    public String sessionId() {
        return sessionId;
    }
}
