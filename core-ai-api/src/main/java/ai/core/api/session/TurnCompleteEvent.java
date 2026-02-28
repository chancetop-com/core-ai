package ai.core.api.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class TurnCompleteEvent implements AgentEvent {
    @Override
    public String sessionId() {
        return sessionId;
    }

    public static TurnCompleteEvent of(String sessionId, String output) {
        var event = new TurnCompleteEvent();
        event.sessionId = sessionId;
        event.output = output;
        return event;
    }

    @NotNull
    @Property(name = "sessionId")
    public String sessionId;

    @Property(name = "output")
    public String output;
}
