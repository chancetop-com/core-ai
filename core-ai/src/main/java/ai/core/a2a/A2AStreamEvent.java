package ai.core.a2a;

import ai.core.api.a2a.JsonRpcError;
import ai.core.api.a2a.Message;
import ai.core.api.a2a.StreamResponse;
import ai.core.api.a2a.Task;
import ai.core.api.a2a.TaskArtifactUpdateEvent;
import ai.core.api.a2a.TaskStatusUpdateEvent;

/**
 * Framework-level union for A2A streaming updates.
 *
 * @author xander
 */
public class A2AStreamEvent {
    public static A2AStreamEvent ofMessage(Message message) {
        var event = new A2AStreamEvent();
        event.message = message;
        event.response = StreamResponse.ofMessage(message);
        return event;
    }

    public static A2AStreamEvent ofTask(Task task) {
        var event = new A2AStreamEvent();
        event.task = task;
        event.response = StreamResponse.ofTask(task);
        return event;
    }

    public static A2AStreamEvent ofStatusUpdate(TaskStatusUpdateEvent statusUpdate) {
        var event = new A2AStreamEvent();
        event.statusUpdate = statusUpdate;
        event.response = StreamResponse.ofStatusUpdate(statusUpdate);
        return event;
    }

    public static A2AStreamEvent ofArtifactUpdate(TaskArtifactUpdateEvent artifactUpdate) {
        var event = new A2AStreamEvent();
        event.artifactUpdate = artifactUpdate;
        event.response = StreamResponse.ofArtifactUpdate(artifactUpdate);
        return event;
    }

    public static A2AStreamEvent ofResponse(StreamResponse response) {
        if (response.task != null) return ofTask(response.task);
        if (response.message != null) return ofMessage(response.message);
        if (response.statusUpdate != null) return ofStatusUpdate(response.statusUpdate);
        if (response.artifactUpdate != null) return ofArtifactUpdate(response.artifactUpdate);
        var event = new A2AStreamEvent();
        event.response = response;
        return event;
    }

    public static A2AStreamEvent ofError(JsonRpcError error) {
        var event = new A2AStreamEvent();
        event.error = error;
        return event;
    }

    public Message message;
    public Task task;
    public TaskStatusUpdateEvent statusUpdate;
    public TaskArtifactUpdateEvent artifactUpdate;
    public JsonRpcError error;
    public StreamResponse response;
}
