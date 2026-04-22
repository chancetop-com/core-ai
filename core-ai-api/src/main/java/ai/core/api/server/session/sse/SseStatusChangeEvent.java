package ai.core.api.server.session.sse;

import ai.core.api.server.session.SessionStatus;
import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class SseStatusChangeEvent extends SseBaseEvent {
    @NotNull
    @Property(name = "status")
    public SessionStatus status;
}
