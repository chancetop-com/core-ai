package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class TurnCompleteEvent implements AgentEvent {
    public static TurnCompleteEvent of(String sessionId, String output) {
        var event = new TurnCompleteEvent();
        event.sessionId = sessionId;
        event.output = output;
        event.cancelled = false;
        return event;
    }

    public static TurnCompleteEvent cancelled(String sessionId) {
        var event = new TurnCompleteEvent();
        event.sessionId = sessionId;
        event.output = "";
        event.cancelled = true;
        return event;
    }

    @NotNull
    @Property(name = "sessionId")
    public String sessionId;

    @Property(name = "output")
    public String output;

    @NotNull
    @Property(name = "cancelled")
    public Boolean cancelled;

    @NotNull
    @Property(name = "max_turns_reached")
    public Boolean maxTurnsReached = false;

    @Property(name = "input_tokens")
    public Long inputTokens;

    @Property(name = "output_tokens")
    public Long outputTokens;

    @Override
    public String sessionId() {
        return sessionId;
    }
}
