package ai.core.llm.domain;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class Choice {
    public static Choice of(FinishReason finishReason, Message message) {
        var choice = new Choice();
        var msg = new AssistantMessage();
        msg.role = message.role;
        msg.content = message.content.getFirst().text;
        msg.reasoningContent = message.reasoningContent;
        msg.name = message.name;
        msg.toolCallId = message.toolCallId;
        msg.toolCalls = message.toolCalls;
        msg.functionCall = message.functionCall;
        choice.message = msg;
        choice.finishReason = finishReason;
        return choice;
    }

    @NotNull
    @Property(name = "finish_reason")
    public FinishReason finishReason;
    @Property(name = "message")
    public AssistantMessage message;
    @Property(name = "delta")
    public AssistantMessage delta;
    @Property(name = "index")
    public Integer index;
}
