package ai.core.api.server.session.sse;

import ai.core.api.server.session.EventType;
import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class SseStartEvent extends SseBaseEvent {
    @NotNull
    @Property(name = "type")
    public EventType type;

    @NotNull
    @Property(name = "sessionId")
    public String sessionId;

    @NotNull
    @Property(name = "data")
    public String data;
}
