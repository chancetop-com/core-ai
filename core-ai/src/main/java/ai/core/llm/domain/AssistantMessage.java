package ai.core.llm.domain;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class AssistantMessage {
    @NotNull
    @Property(name = "role")
    public RoleType role = RoleType.ASSISTANT;
    @NotNull
    @Property(name = "content")
    public String content;
    @Property(name = "reasoning_content")
    public String reasoningContent;
    @Property(name = "name")
    public String name;
    @Property(name = "tool_call_id")
    public String toolCallId;
    @Property(name = "function_call")
    public FunctionCall functionCall;
    @Property(name = "tool_calls")
    public List<FunctionCall> toolCalls;

    public Message toMessage() {
        return Message.of(role, content, name, toolCallId, functionCall, toolCalls);
    }
}
