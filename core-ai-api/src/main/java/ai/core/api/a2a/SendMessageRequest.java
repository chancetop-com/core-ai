package ai.core.api.a2a;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * Request for SendMessage and SendStreamingMessage.
 *
 * @author xander
 */
public class SendMessageRequest {
    @Property(name = "tenant")
    public String tenant;

    @Property(name = "message")
    public Message message;

    @Property(name = "configuration")
    public SendMessageConfiguration configuration;

    @Property(name = "metadata")
    public Map<String, Object> metadata;

    public String extractUserText() {
        if (message == null) return "";
        return message.extractText();
    }
}
