package ai.core.api.a2a;

import core.framework.api.json.Property;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class Message {
    public static Message user(String text) {
        var msg = new Message();
        msg.role = "ROLE_USER";
        msg.parts = List.of(Part.text(text));
        return msg;
    }

    public static Message agent(String text) {
        var msg = new Message();
        msg.role = "ROLE_AGENT";
        msg.parts = List.of(Part.text(text));
        return msg;
    }

    @Property(name = "role")
    public String role;

    @Property(name = "parts")
    public List<Part> parts;

    @Property(name = "messageId")
    public String messageId;

    @Property(name = "taskId")
    public String taskId;

    @Property(name = "contextId")
    public String contextId;

    @Property(name = "referenceTaskIds")
    public List<String> referenceTaskIds;

    @Property(name = "metadata")
    public Map<String, Object> metadata;

    @Property(name = "extensions")
    public List<String> extensions;

    public String extractText() {
        if (parts == null || parts.isEmpty()) return "";
        var sb = new StringBuilder();
        for (var part : parts) {
            if (isTextPart(part) && part.text != null) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(part.text);
            }
        }
        return sb.toString();
    }

    private boolean isTextPart(Part part) {
        return part.text != null;
    }
}
