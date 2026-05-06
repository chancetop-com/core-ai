package ai.core.a2a;

import ai.core.api.a2a.Message;
import ai.core.api.a2a.SendMessageResponse;
import ai.core.api.a2a.Task;

/**
 * Framework-level union for A2A SendMessage results.
 *
 * @author xander
 */
public class A2AInvocationResult {
    public static A2AInvocationResult ofMessage(Message message) {
        var result = new A2AInvocationResult();
        result.message = message;
        result.response = SendMessageResponse.ofMessage(message);
        return result;
    }

    public static A2AInvocationResult ofTask(Task task) {
        var result = new A2AInvocationResult();
        result.task = task;
        result.response = SendMessageResponse.ofTask(task);
        return result;
    }

    public Message message;
    public Task task;
    public SendMessageResponse response;

    public boolean hasTask() {
        return task != null;
    }

    public boolean hasMessage() {
        return message != null;
    }
}
