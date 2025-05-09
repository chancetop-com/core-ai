package ai.core.task;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class TaskMessage {
    public TaskRoleType role;
    public List<Part<?>> parts;
    public Map<String, String> metadata;
}
