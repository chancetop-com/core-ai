package ai.core.agent;

import ai.core.llm.domain.Content;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class AgentFootprintCalculatorTest {
    @Test
    void countsHistoryOnceAndCountsUserTurns() {
        var user1 = Message.of(RoleType.USER, "hi");
        var assistant1 = Message.of(RoleType.ASSISTANT, "hello");
        var user2 = Message.of(RoleType.USER, "again");
        var assistant2 = Message.of(RoleType.ASSISTANT, "done");
        var messages = List.of(user1, assistant1, user2, assistant2);
        var history = List.of(user1, assistant1, user2, assistant2);

        var footprint = AgentFootprintCalculator.calculate(messages, history);

        assertEquals(2, footprint.turnCount());
        assertEquals(4, footprint.messageCount());
        assertEquals(4, footprint.historyCount());
        assertEquals(2 + 5 + 5 + 4, footprint.textBytes());
        assertEquals(2 + 5 + 5 + 4 + 4 * 256 + 4096, footprint.estimatedBytes());
    }

    @Test
    void countsInlineImagesAndToolPayloads() {
        var imageUrl = "data:image/png;base64,AAAA";
        var image = Message.of(RoleType.USER, Content.of(Content.ImageUrl.of(imageUrl, "png")));
        var call = FunctionCall.of("call-1", "function", "lookup", "{\"id\":1}");
        var toolCall = Message.of(RoleType.ASSISTANT, "", null, null, List.of(call));
        var toolResult = Message.of(RoleType.TOOL, "tool result");

        var footprint = AgentFootprintCalculator.calculate(List.of(image, toolCall, toolResult), List.of());

        assertEquals(1, footprint.turnCount());
        assertEquals(imageUrl.length(), footprint.inlineImageBytes());
        assertEquals(28, footprint.toolCallBytes());
        assertEquals(11, footprint.toolResultBytes());
        assertEquals(11, footprint.textBytes());
        assertEquals(imageUrl.length() + 28 + 11 + 3 * 256 + 4096, footprint.estimatedBytes());
        assertTrue(footprint.contextTokens() > 0);
    }

    @Test
    void interruptionMarkerIsNotCountedAsATurn() {
        var marker = Message.of(RoleType.USER, "<system-reminder>The previous turn was interrupted");
        var user = Message.of(RoleType.USER, "continue");
        var messages = List.of(marker, user);

        var footprint = AgentFootprintCalculator.calculate(messages, List.of());

        assertEquals(1, footprint.turnCount());
        assertEquals(marker.getTextContent().length() + 8 + 2 * 256 + 4096, footprint.estimatedBytes());
    }
}
