package ai.core.server.web;

import ai.core.api.session.EventType;
import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class SseAgentEvent {
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
