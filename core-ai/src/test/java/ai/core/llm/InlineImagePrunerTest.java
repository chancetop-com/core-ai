package ai.core.llm;

import ai.core.llm.domain.Content;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the request-time inline image budget: newest images survive, older ones become stable
 * text placeholders, and the caller's messages are never mutated.
 *
 * @author stephen
 */
class InlineImagePrunerTest {
    @Test
    void keepsNewestImagesWithinCountBudget() {
        var messages = List.of(
                userMessage("first", imagePart(4000)),
                userMessage("second", imagePart(4000)),
                userMessage("third", imagePart(4000)));

        var result = InlineImagePruner.prune(messages, 2, Long.MAX_VALUE);

        assertEquals(1, result.prunedCount());
        assertEquals(3000, result.prunedBytes());
        assertFalse(hasImagePart(result.messages().get(0)));
        assertTrue(result.messages().get(0).content.get(1).text.contains("Image omitted"));
        assertTrue(hasImagePart(result.messages().get(1)));
        assertTrue(hasImagePart(result.messages().get(2)));
    }

    @Test
    void keepsNewestImagesWithinByteBudget() {
        var messages = List.of(
                userMessage("first", imagePart(4000)),
                userMessage("second", imagePart(4000)),
                userMessage("third", imagePart(4000)));

        // newest 3000 decoded bytes kept, the second one still fits the 7000 byte budget, the first does not
        var result = InlineImagePruner.prune(messages, 8, 7000);

        assertEquals(1, result.prunedCount());
        assertFalse(hasImagePart(result.messages().get(0)));
        assertTrue(hasImagePart(result.messages().get(1)));
        assertTrue(hasImagePart(result.messages().get(2)));
    }

    @Test
    void neverHidesTheNewestImageEvenWhenItExceedsTheByteBudget() {
        var messages = List.of(
                userMessage("first", imagePart(4000)),
                userMessage("second", imagePart(400000)));

        var result = InlineImagePruner.prune(messages, 8, 1000);

        assertEquals(1, result.prunedCount());
        assertFalse(hasImagePart(result.messages().get(0)));
        assertTrue(hasImagePart(result.messages().get(1)));
    }

    @Test
    void zeroBudgetDropsEveryInlineImage() {
        var messages = List.of(
                userMessage("first", imagePart(4000)),
                userMessage("second", imagePart(4000)));

        var result = InlineImagePruner.prune(messages, 0, 0);

        assertEquals(2, result.prunedCount());
        assertEquals(6000, result.prunedBytes());
        assertTrue(result.messages().stream().noneMatch(this::hasImagePart));
    }

    @Test
    void negativeBudgetsDisableBothLimits() {
        var messages = List.of(
                userMessage("first", imagePart(4000)),
                userMessage("second", imagePart(400000)));

        var result = InlineImagePruner.prune(messages, -1, -1);

        assertEquals(0, result.prunedCount());
        assertSame(messages, result.messages());
    }

    @Test
    void urlImagesAreNotCountedOrPruned() {
        var messages = List.of(
                userMessage("first", Content.of(Content.ImageUrl.of("https://blob/keyframe.png", null))),
                userMessage("second", imagePart(4000)),
                userMessage("third", imagePart(4000)));

        var result = InlineImagePruner.prune(messages, 1, Long.MAX_VALUE);

        assertEquals(1, result.prunedCount());
        assertTrue(hasImagePart(result.messages().get(0)));
        assertFalse(hasImagePart(result.messages().get(1)));
        assertTrue(hasImagePart(result.messages().get(2)));
    }

    @Test
    void nothingIsPrunedWhenEverythingFits() {
        var messages = List.of(userMessage("first", imagePart(4000)));

        var result = InlineImagePruner.prune(messages);

        assertEquals(0, result.prunedCount());
        assertSame(messages, result.messages());
    }

    @Test
    void secondPassIsIdempotent() {
        var messages = List.of(
                userMessage("first", imagePart(4000)),
                userMessage("second", imagePart(4000)));

        var first = InlineImagePruner.prune(messages, 1, Long.MAX_VALUE);
        var second = InlineImagePruner.prune(first.messages(), 1, Long.MAX_VALUE);

        assertEquals(1, first.prunedCount());
        assertEquals(0, second.prunedCount());
        assertSame(first.messages(), second.messages());
    }

    @Test
    void placeholderTextIsStableForRepeatedRequests() {
        var messages = List.of(
                userMessage("first", imagePart(4000)),
                userMessage("second", imagePart(4000)));

        var first = InlineImagePruner.prune(messages, 1, Long.MAX_VALUE);
        var repeated = InlineImagePruner.prune(messages, 1, Long.MAX_VALUE);

        assertEquals(first.messages().getFirst().content.get(1).text,
                repeated.messages().getFirst().content.get(1).text);
    }

    @Test
    void callerMessagesKeepTheirImages() {
        var messages = List.of(
                userMessage("first", imagePart(4000)),
                userMessage("second", imagePart(4000)));

        InlineImagePruner.prune(messages, 1, Long.MAX_VALUE);

        assertTrue(hasImagePart(messages.get(0)));
        assertTrue(hasImagePart(messages.get(1)));
    }

    @Test
    void toolPlaceholderNamesTheToolThatProducedTheImage() {
        var message = Message.of(new Message.MessageRecord(RoleType.TOOL,
                List.of(Content.of("image loaded"), imagePart(4000)), null, "read_file", "call-1", null));

        var result = InlineImagePruner.prune(List.of(message), 0, 0);

        assertEquals("call-1", result.messages().getFirst().toolCallId);
        assertEquals("read_file", result.messages().getFirst().name);
        assertEquals("image loaded", result.messages().getFirst().content.getFirst().text);
        assertTrue(result.messages().getFirst().content.get(1).text.contains("read_file"));
    }

    @Test
    void nullAndEmptyMessageListsAreSafe() {
        assertNull(InlineImagePruner.prune(null).messages());
        assertEquals(0, InlineImagePruner.prune(List.of()).prunedCount());
    }

    private Message userMessage(String text, Content image) {
        return Message.of(new Message.MessageRecord(RoleType.USER, List.of(Content.of(text), image), null, null, null, null));
    }

    private Content imagePart(int base64Chars) {
        return Content.of(Content.ImageUrl.of("data:image/png;base64," + "A".repeat(base64Chars), "image/png"));
    }

    private boolean hasImagePart(Message message) {
        return message.content != null && message.content.stream().anyMatch(part -> part.type == Content.ContentType.IMAGE_URL);
    }
}
