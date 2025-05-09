package ai.core.task;

import ai.core.a2a.A2ARequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class Task {
    public static Task newTask(A2ARequest request) {
        var task = new Task();
        task.id = request.id;
        task.status = TaskStatus.SUBMITTED;
        task.history = new ArrayList<>();
        task.history.add(request.message);
        task.artifacts = new ArrayList<>();
        task.metadata = request.metadata;
        return task;
    }

    private String id;
    private TaskStatus status;
    private List<TaskMessage> history;
    private List<TaskArtifact> artifacts;
    private Map<String, String> metadata;

    public String getId() {
        return id;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public List<TaskArtifact> getArtifacts() {
        return artifacts;
    }

    public void addArtifacts(List<TaskArtifact> artifacts) {
        this.artifacts.addAll(artifacts);
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    public List<TaskMessage> getHistory() {
        return history;
    }

    public void addHistories(List<TaskMessage> history) {
        this.history.addAll(history);
    }

    public TaskMessage getLastMessage() {
        return this.history.getLast();
    }
}
