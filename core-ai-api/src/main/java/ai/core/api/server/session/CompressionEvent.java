package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author xander
 */
public class CompressionEvent implements AgentEvent {
    public static CompressionEvent of(String sessionId, int beforeCount, int afterCount, boolean completed) {
        var event = new CompressionEvent();
        event.sessionId = sessionId;
        event.beforeCount = beforeCount;
        event.afterCount = afterCount;
        event.completed = completed;
        return event;
    }

    @NotNull
    @Property(name = "sessionId")
    public String sessionId;

    @NotNull
    @Property(name = "before_count")
    public Integer beforeCount;

    @NotNull
    @Property(name = "after_count")
    public Integer afterCount;

    @NotNull
    @Property(name = "completed")
    public Boolean completed;

    @Override
    public String sessionId() {
        return sessionId;
    }
}
