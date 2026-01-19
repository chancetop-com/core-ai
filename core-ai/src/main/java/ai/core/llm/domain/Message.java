package ai.core.llm.domain;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class Message {
    public static Message of(RoleType role, String content) {
        var message = new Message();
        message.role = role;
        message.content = List.of(Content.of(content));
        return message;
    }

    public static Message of(RoleType role, Content content) {
        var message = new Message();
        message.role = role;
        message.content = List.of(content);
        return message;
    }

    public static Message of(RoleType role, String content, String name, String toolCallId, FunctionCall functionCall, List<FunctionCall> toolCalls) {
        var message = new Message();
        message.role = role;
        message.content = List.of(Content.of(content));
        message.name = name;
        message.toolCallId = toolCallId;
        message.functionCall = functionCall;
        message.toolCalls = toolCalls;
        return message;
    }

    public static Message of(MessageRecord record) {
        var message = new Message();
        message.role = record.role;
        message.content = record.content;
        message.name = record.name;
        message.toolCallId = record.toolCallId;
        message.functionCall = record.functionCall;
        message.toolCalls = record.toolCalls;
        return message;
    }

    @NotNull
    @Property(name = "role")
    public RoleType role;
    @NotNull
    @Property(name = "content")
    public List<Content> content;
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

    public String getName() {
        return name;
    }

    public String getTextContent() {
        if (content == null || content.isEmpty()) return null;
        return content.getFirst().text;
    }

    public record MessageRecord(RoleType role,
                                List<Content> content,
                                String reasoningContent,
                                String name,
                                String toolCallId,
                                FunctionCall functionCall,
                                List<FunctionCall> toolCalls) {
    }
}
