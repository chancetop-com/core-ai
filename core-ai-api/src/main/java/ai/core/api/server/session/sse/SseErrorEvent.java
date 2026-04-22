package ai.core.api.server.session.sse;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class SseErrorEvent extends SseBaseEvent {
    @NotNull
    @Property(name = "message")
    public String message;

    @Property(name = "detail")
    public String detail;
}
