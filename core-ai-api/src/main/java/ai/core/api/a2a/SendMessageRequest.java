package ai.core.api.a2a;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class SendMessageRequest {
    @Property(name = "message")
    public Message message;

    @Property(name = "configuration")
    public TaskConfiguration configuration;

    public String extractUserText() {
        if (message == null) return "";
        return message.extractText();
    }

    public static class TaskConfiguration {
        @Property(name = "acceptedOutputModes")
        public java.util.List<String> acceptedOutputModes;

        @Property(name = "blocking")
        public Boolean blocking;
    }
}
