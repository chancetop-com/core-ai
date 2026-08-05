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
        event.cancelled = Boolean.FALSE;
        return event;
    }

    public static TurnCompleteEvent cancelled(String sessionId) {
        return cancelled(sessionId, "");
    }

    /**
     * Cancelled turn that may still carry partial output (e.g. text produced before ESC),
     * so persistence listeners can record what was produced before the interruption.
     */
    public static TurnCompleteEvent cancelled(String sessionId, String output) {
        var event = new TurnCompleteEvent();
        event.sessionId = sessionId;
        event.output = output != null ? output : "";
        event.cancelled = Boolean.TRUE;
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
    public Boolean maxTurnsReached = Boolean.FALSE;

    @Property(name = "input_tokens")
    public Long inputTokens;

    @Property(name = "output_tokens")
    public Long outputTokens;

    @Property(name = "cost_usd")
    public Double costUsd;

    @Override
    public String sessionId() {
        return sessionId;
    }
}
