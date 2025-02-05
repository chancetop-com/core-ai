package ai.core.litellm.completion;

import core.framework.api.json.Property;
import core.framework.api.validate.Max;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class CreateCompletionAJAXRequest {
    @NotNull
    @Property(name = "model")
    public String model = "gpt-3.5-turbo-instruct";

    @NotNull
    @Property(name = "messages")
    public List<MessageAJAXView> messages;

    @NotNull
    @Max(2)
    @Property(name = "temperature")
    public Double temperature = 0.7d;

    @Property(name = "tools")
    public List<ToolAJAXView> tools;

    @Property(name = "tool_choice")
    public String toolChoice;
}
