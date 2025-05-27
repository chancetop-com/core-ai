package ai.core.llm.providers.inner.litellm;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class MessageAJAXView {
    @NotNull
    @Property(name = "role")
    public RoleTypeAJAXView role;

    @Property(name = "content")
    public String content;

    @Property(name = "name")
    public String name;

    @Property(name = "tool_calls")
    public List<FunctionCallAJAXView> toolCalls;

    @Property(name = "function_call")
    public FunctionCallAJAXView functionCall;

    @Property(name = "tool_call_id")
    public String toolCallId;
}
