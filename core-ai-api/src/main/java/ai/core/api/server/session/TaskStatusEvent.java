package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * Dispatched when a background task (launched via the task tool with run_in_background=true)
 * reaches a terminal state: completed, failed, or cancelled.
 *
 * @author stephen
 */
public class TaskStatusEvent implements AgentEvent {
    public static TaskStatusEvent of(String sessionId, String taskId, String status) {
        var event = new TaskStatusEvent();
        event.sessionId = sessionId;
        event.taskId = taskId;
        event.status = status;
        return event;
    }

    @NotNull
    @Property(name = "sessionId")
    public String sessionId;

    @NotNull
    @Property(name = "taskId")
    public String taskId;

    @NotNull
    @Property(name = "status")
    public String status;

    @Override
    public String sessionId() {
        return sessionId;
    }
}
