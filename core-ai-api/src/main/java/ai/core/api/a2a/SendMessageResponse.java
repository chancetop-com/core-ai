package ai.core.api.a2a;

import core.framework.api.json.Property;

/**
 * Oneof response for SendMessage.
 *
 * @author xander
 */
public class SendMessageResponse {
    public static SendMessageResponse ofTask(Task task) {
        var response = new SendMessageResponse();
        response.task = task;
        return response;
    }

    public static SendMessageResponse ofMessage(Message message) {
        var response = new SendMessageResponse();
        response.message = message;
        return response;
    }

    @Property(name = "task")
    public Task task;

    @Property(name = "message")
    public Message message;
}
