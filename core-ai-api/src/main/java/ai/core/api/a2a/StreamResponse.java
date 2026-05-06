package ai.core.api.a2a;

import core.framework.api.json.Property;

/**
 * Oneof response item for streaming operations and push notification payloads.
 *
 * @author xander
 */
public class StreamResponse {
    public static StreamResponse ofTask(Task task) {
        var response = new StreamResponse();
        response.task = task;
        return response;
    }

    public static StreamResponse ofMessage(Message message) {
        var response = new StreamResponse();
        response.message = message;
        return response;
    }

    public static StreamResponse ofStatusUpdate(TaskStatusUpdateEvent statusUpdate) {
        var response = new StreamResponse();
        response.statusUpdate = statusUpdate;
        return response;
    }

    public static StreamResponse ofArtifactUpdate(TaskArtifactUpdateEvent artifactUpdate) {
        var response = new StreamResponse();
        response.artifactUpdate = artifactUpdate;
        return response;
    }

    @Property(name = "task")
    public Task task;

    @Property(name = "message")
    public Message message;

    @Property(name = "statusUpdate")
    public TaskStatusUpdateEvent statusUpdate;

    @Property(name = "artifactUpdate")
    public TaskArtifactUpdateEvent artifactUpdate;
}
