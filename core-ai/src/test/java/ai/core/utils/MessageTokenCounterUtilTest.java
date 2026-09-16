package ai.core.utils;

import ai.core.llm.domain.Content;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author xander
 */
class MessageTokenCounterUtilTest {

    @Test
    void countsEveryTextPart() {
        var message = new Message();
        message.role = RoleType.USER;
        message.content = List.of(Content.of("hello world"), Content.of("second block"));

        var expected = MessageTokenCounterUtil.count(Message.of(RoleType.USER, "hello world"))
                + MessageTokenCounterUtil.count(Message.of(RoleType.USER, "second block"));

        assertEquals(expected, MessageTokenCounterUtil.count(message));
    }

    @Test
    void countsImagePartsTheTokenizerCannotSee() {
        var message = new Message();
        message.role = RoleType.USER;
        message.content = List.of(Content.of("look at this"),
                Content.of(Content.ImageUrl.of("data:image/png;base64,iVBORw0KGgo=", "png")));

        assertTrue(MessageTokenCounterUtil.count(message) > MessageTokenCounterUtil.count(Message.of(RoleType.USER, "look at this")));
    }

    @Test
    void countsToolCallArgumentsAndReasoning() {
        var message = Message.of(RoleType.ASSISTANT, "answer");
        message.toolCalls = List.of(FunctionCall.of("call-1", "function", "search", "{\"query\": \"core-ai\"}"));
        message.reasoningContent = "thinking about the query";

        assertTrue(MessageTokenCounterUtil.count(message) > MessageTokenCounterUtil.count(Message.of(RoleType.ASSISTANT, "answer")));
    }
}
