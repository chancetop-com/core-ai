package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class ReasoningCompleteEvent implements AgentEvent {
    public static ReasoningCompleteEvent of(String sessionId, String reasoning) {
        var event = new ReasoningCompleteEvent();
        event.sessionId = sessionId;
        event.reasoning = reasoning;
        return event;
    }

    @NotNull
    @Property(name = "sessionId")
    public String sessionId;

    @NotNull
    @Property(name = "reasoning")
    public String reasoning;

    @Override
    public String sessionId() {
        return sessionId;
    }
}
