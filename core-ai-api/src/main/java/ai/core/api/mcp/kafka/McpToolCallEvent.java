package ai.core.api.mcp.kafka;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class McpToolCallEvent {
    public static McpToolCallEvent of(String id, String text) {
        var message = new McpToolCallEvent();
        message.id = id;
        message.text = text;
        return message;
    }

    @NotNull
    @NotBlank
    @Property(name = "id")
    public String id;

    @NotNull
    @NotBlank
    @Property(name = "text")
    public String text;
}
