package ai.core.api.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class ReasoningCompleteEvent implements AgentEvent {
    @Override
    public String sessionId() {
        return sessionId;
    }

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
}
