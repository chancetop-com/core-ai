package ai.core.api.server.session.sse;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class SseCompressionEvent extends SseBaseEvent {
    @NotNull
    @Property(name = "before_count")
    public Integer beforeCount;

    @NotNull
    @Property(name = "after_count")
    public Integer afterCount;

    @NotNull
    @Property(name = "completed")
    public Boolean completed;
}
