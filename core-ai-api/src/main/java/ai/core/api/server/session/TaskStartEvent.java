package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * author: lim chen
 * date: 2026/4/8
 * description:
 */
public class TaskStartEvent implements AgentEvent {
    public static TaskStartEvent of(String sessionId, String taskId, String taskName) {
        var event = new TaskStartEvent();
        event.sessionId = sessionId;
        event.taskId = taskId;
        event.taskName = taskName;
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


    @Override
    public String sessionId() {
        return sessionId;
    }
}
