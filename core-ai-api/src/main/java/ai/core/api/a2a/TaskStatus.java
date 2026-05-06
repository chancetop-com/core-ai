package ai.core.api.a2a;

import core.framework.api.json.Property;

import java.time.Instant;

/**
 * @author stephen
 */
public class TaskStatus {
    public static TaskStatus of(TaskState state) {
        var status = new TaskStatus();
        status.state = state;
        return status;
    }

    public static TaskStatus of(TaskState state, Message message) {
        var status = new TaskStatus();
        status.state = state;
        status.message = message;
        return status;
    }

    @Property(name = "state")
    public TaskState state;

    @Property(name = "message")
    public Message message;

    @Property(name = "timestamp")
    public Instant timestamp;
}
