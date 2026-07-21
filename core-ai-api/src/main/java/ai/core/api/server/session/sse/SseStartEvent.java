package ai.core.api.server.session.sse;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class SseStartEvent extends SseBaseEvent {
    @NotNull
    @Property(name = "data")
    public String data;
}
