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
        return of(sessionId, taskId, status, null);
    }

    public static TaskStatusEvent of(String sessionId, String taskId, String status, String toolName) {
        var event = new TaskStatusEvent();
        event.sessionId = sessionId;
        event.taskId = taskId;
        event.status = status;
        event.toolName = toolName;
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

    /** the tool that owns the task, when the source knows it; sub-agent tasks have none */
    @Property(name = "toolName")
    public String toolName;

    @Override
    public String sessionId() {
        return sessionId;
    }
}
