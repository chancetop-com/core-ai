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

    @NotNull
    @Property(name = "context_tokens")
    public Integer contextTokens;

    @NotNull
    @Property(name = "max_context_tokens")
    public Integer maxContextTokens;

    @NotNull
    @Property(name = "trigger_threshold")
    public Double triggerThreshold;

    /**
     * Describes how much of the model context the conversation occupied when compression started, so a
     * client can show the compression in the same terms the trigger was decided in.
     */
    public CompressionEvent withContext(int contextTokens, int maxContextTokens, double triggerThreshold) {
        this.contextTokens = contextTokens;
        this.maxContextTokens = maxContextTokens;
        this.triggerThreshold = triggerThreshold;
        return this;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }
}
