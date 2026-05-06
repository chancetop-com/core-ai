package ai.core.api.a2a;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * Streaming update for a task status transition.
 *
 * @author xander
 */
public class TaskStatusUpdateEvent {
    @Property(name = "taskId")
    public String taskId;

    @Property(name = "contextId")
    public String contextId;

    @Property(name = "status")
    public TaskStatus status;

    @Property(name = "metadata")
    public Map<String, Object> metadata;
}
