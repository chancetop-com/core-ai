package ai.core.task;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class Task {
    public String id;
    public TaskStatus status;
    public List<TaskMessage> history;
    public List<TaskArtifact> artifacts;
    public Map<String, String> metadata;
}
