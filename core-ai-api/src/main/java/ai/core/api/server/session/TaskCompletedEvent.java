package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * Dispatched when a background subagent task completes or fails.
 * Carries the taskId so CLI can match it to the earlier "Running in background" line.
 */
public class TaskCompletedEvent implements AgentEvent {

    public static TaskCompletedEvent of(String sessionId, String taskId, String taskName, String status, String resultPreview) {
        var event = new TaskCompletedEvent();
        event.sessionId = sessionId;
        event.taskId = taskId;
        event.status = status;
        event.taskName = taskName;
        event.resultPreview = resultPreview != null ? resultPreview : "";
        return event;
    }

    @NotNull
    @Property(name = "sessionId")
    public String sessionId;

    @NotNull
    @Property(name = "taskId")
    public String taskId;
    @NotNull
    @Property(name = "taskName")
    public String taskName;
    @NotNull
    @Property(name = "status")
    public String status;

    @NotNull
    @Property(name = "resultPreview")
    public String resultPreview;

    @Override
    public String sessionId() {
        return sessionId;
    }
}
