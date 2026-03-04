package ai.core.api.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class TextChunkEvent implements AgentEvent {
    public static TextChunkEvent of(String sessionId, String chunk) {
        var event = new TextChunkEvent();
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
