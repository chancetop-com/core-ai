package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class ReasoningChunkEvent implements AgentEvent {
    public static ReasoningChunkEvent of(String sessionId, String chunk) {
        var event = new ReasoningChunkEvent();
        event.sessionId = sessionId;
        event.chunk = chunk;
        return event;
    }

    @NotNull
    @Property(name = "sessionId")
    public String sessionId;

    @NotNull
    @Property(name = "chunk")
    public String chunk;

    @Override
    public String sessionId() {
        return sessionId;
    }
}
