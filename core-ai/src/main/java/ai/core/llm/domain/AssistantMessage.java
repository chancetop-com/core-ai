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
    @Property(name = "tool_calls")
    public List<FunctionCall> toolCalls;

    private transient StringBuilder contentBuilder;
    private transient StringBuilder reasoningContentBuilder;

    public void appendContent(String chunk) {
        if (contentBuilder == null) {
            contentBuilder = new StringBuilder(content != null ? content : "");
        }
        contentBuilder.append(chunk);
    }

    public void appendReasoningContent(String chunk) {
        if (reasoningContentBuilder == null) {
            reasoningContentBuilder = new StringBuilder(reasoningContent != null ? reasoningContent : "");
        }
        reasoningContentBuilder.append(chunk);
    }

    public void finalizeStreamingFields() {
        if (contentBuilder != null) {
            content = contentBuilder.toString();
            contentBuilder = null;
        }
        if (reasoningContentBuilder != null) {
            reasoningContent = reasoningContentBuilder.toString();
            reasoningContentBuilder = null;
        }
        if (toolCalls != null) {
            for (FunctionCall tc : toolCalls) {
                if (tc != null && tc.function != null) {
                    tc.function.finalizeStreamingFields();
                }
            }
        }
    }

    public Message toMessage() {
        return Message.of(role, content, name, toolCallId, toolCalls, reasoningContent);
    }
}
