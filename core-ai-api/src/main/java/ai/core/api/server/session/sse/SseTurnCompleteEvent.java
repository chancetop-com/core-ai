package ai.core.api.server.session.sse;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class SseTurnCompleteEvent extends SseBaseEvent {
    @Property(name = "output")
    public String output;

    @Property(name = "cancelled")
    public Boolean cancelled;

    @Property(name = "max_turns_reached")
    public Boolean maxTurnsReached;

    @Property(name = "input_tokens")
    public Long inputTokens;

    @Property(name = "output_tokens")
    public Long outputTokens;
}
