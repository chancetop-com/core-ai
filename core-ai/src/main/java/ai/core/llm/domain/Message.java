package ai.core.llm.domain;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.io.IOException;
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

    public static Message of(RoleType role, String content, String name, String toolCallId, List<FunctionCall> toolCalls) {
        var message = new Message();
        message.role = role;
        message.content = List.of(Content.of(content));
        message.name = name;
        message.toolCallId = toolCallId;
        message.toolCalls = toolCalls;
        return message;
    }

    public static Message of(RoleType role, String content, String name, String toolCallId, List<FunctionCall> toolCalls, String reasoningContent) {
        var message = new Message();
        message.role = role;
        message.content = List.of(Content.of(content));
        message.name = name;
        message.toolCallId = toolCallId;
        message.toolCalls = toolCalls;
        message.reasoningContent = reasoningContent;
        return message;
    }

    public static Message of(MessageRecord record) {
        var message = new Message();
        message.role = record.role;
        message.content = record.content;
        message.name = record.name;
        message.toolCallId = record.toolCallId;
        message.toolCalls = record.toolCalls;
        return message;
    }

    @NotNull
    @Property(name = "role")
    public RoleType role;
    @NotNull
    @Property(name = "content")
    @JsonDeserialize(using = ContentListDeserializer.class)
    public List<Content> content;
    @Property(name = "reasoning_content")
    public String reasoningContent;
    @Property(name = "name")
    public String name;
    @Property(name = "tool_call_id")
    public String toolCallId;
    @Property(name = "tool_calls")
    public List<FunctionCall> toolCalls;

    public String getName() {
        return name;
    }

    public String getTextContent() {
        if (content == null || content.isEmpty()) return null;
        return content.getFirst().text;
    }

    @Override
    public String toString() {
        return JsonUtil.toJson(this);
    }

    public record MessageRecord(RoleType role,
                                List<Content> content,
                                String reasoningContent,
                                String name,
                                String toolCallId,
                                List<FunctionCall> toolCalls) {
    }

    public static class ContentListDeserializer extends JsonDeserializer<List<Content>> {
        @Override
        public List<Content> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                return List.of(Content.of(p.getText()));
            }
            var listType = ctxt.getTypeFactory().constructCollectionType(List.class, Content.class);
            return ctxt.readValue(p, listType);
        }
    }
}
