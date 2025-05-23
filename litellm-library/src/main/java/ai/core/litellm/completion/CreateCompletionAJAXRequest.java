package ai.core.litellm.completion;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class CreateCompletionAJAXRequest {
    @NotNull
    @Property(name = "model")
    public String model;

    @NotNull
    @Property(name = "messages")
    public List<MessageAJAXView> messages;

    @Property(name = "temperature")
    public Double temperature;

    @Property(name = "tools")
    public List<ToolAJAXView> tools;

    @Property(name = "tool_choice")
    public String toolChoice;
}
