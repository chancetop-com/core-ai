package ai.core.a2a;

import ai.core.task.TaskMessage;

import java.util.Map;

/**
 * @author stephen
 */
public record A2ARequest(String id, TaskMessage message, Map<String, String> metadata) {

}
