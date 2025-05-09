package ai.core.a2a;

import ai.core.task.TaskMessage;

import java.util.Map;

/**
 * @author stephen
 */
public class A2ARequest {
    public String id;
    public TaskMessage message;
    public Map<String, String> metadata;
}
