package ai.core.api.a2a;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class Message {
    public static Message user(String text) {
        var msg = new Message();
        msg.role = "user";
        msg.parts = List.of(Part.text(text));
        return msg;
    }

    public static Message agent(String text) {
        var msg = new Message();
        msg.role = "agent";
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

    public String extractText() {
        if (parts == null || parts.isEmpty()) return "";
        var sb = new StringBuilder();
        for (var part : parts) {
            if ("text".equals(part.type) && part.text != null) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(part.text);
            }
        }
        return sb.toString();
    }
}
