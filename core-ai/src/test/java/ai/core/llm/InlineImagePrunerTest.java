package ai.core.llm;

import ai.core.llm.domain.Content;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final AtomicInteger IMAGE_SEQUENCE = new AtomicInteger();
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

    @Test
    void anImageHandedOverTwiceIsResentOnce() {
        var messages = List.of(
                userMessage("first", imagePart("A", 4000)),
                userMessage("second", imagePart("B", 4000)),
                userMessage("re-read", imagePart("A", 4000)));

        var result = InlineImagePruner.prune(messages, -1, -1);

        assertEquals(1, result.prunedCount());
        assertFalse(hasImagePart(result.messages().getFirst()));
        assertTrue(hasImagePart(result.messages().get(1)));
        assertTrue(hasImagePart(result.messages().getLast()));
    }

    @Test
    void reReadImagesDoNotEvictUnrelatedOnes() {
        var messages = List.of(
                userMessage("first", imagePart("A", 4000)),
                userMessage("second", imagePart("B", 4000)),
                userMessage("re-read", imagePart("A", 4000)));

        // two distinct images fit a two-image budget even though the newest is a re-read of the first
        var result = InlineImagePruner.prune(messages, 2, Long.MAX_VALUE);

        assertEquals(1, result.prunedCount());
        assertTrue(hasImagePart(result.messages().get(1)));
        assertTrue(hasImagePart(result.messages().getLast()));
    }

    @Test
    void defaultCountBudgetCoversMoreThanOneTurnsReads() {
        assertEquals(16, InlineImagePruner.DEFAULT_MAX_IMAGES);
    }

    @Test
    void newestTurnKeepsAllItsImagesEvenOverTheCountBudget() {
        var messages = List.of(
                userMessageWithImages("references", imagePart(4000), imagePart(4000)),
                userMessageWithImages("compare these", imagePart(4000), imagePart(4000), imagePart(4000)));

        var result = InlineImagePruner.prune(messages, 2, Long.MAX_VALUE);

        // the newest turn is the unit the model must see whole: all three images stay, the older turn goes
        assertEquals(2, result.prunedCount());
        assertFalse(hasImagePart(result.messages().get(0)));
        assertEquals(3, imageCount(result.messages().get(1)));
    }

    @Test
    void olderTurnIsKeptOrDroppedAsAWhole() {
        var messages = List.of(
                userMessageWithImages("references", imagePart(4000), imagePart(4000), imagePart(4000)),
                userMessage("latest", imagePart(4000)));

        var kept = InlineImagePruner.prune(messages, 4, Long.MAX_VALUE);
        assertEquals(0, kept.prunedCount());
        assertEquals(3, imageCount(kept.messages().getFirst()));

        var dropped = InlineImagePruner.prune(messages, 3, Long.MAX_VALUE);
        assertEquals(3, dropped.prunedCount());
        assertFalse(hasImagePart(dropped.messages().getFirst()));
        assertTrue(hasImagePart(dropped.messages().getLast()));
    }

    @Test
    void newestTurnKeepsAllItsImagesEvenOverTheByteBudget() {
        var messages = List.of(
                userMessage("older", imagePart(4000)),
                userMessageWithImages("latest", imagePart(4000), imagePart(4000)));

        var result = InlineImagePruner.prune(messages, 8, 1000);

        assertEquals(1, result.prunedCount());
        assertFalse(hasImagePart(result.messages().getFirst()));
        assertEquals(2, imageCount(result.messages().getLast()));
    }

    @Test
    void textOnlyFollowUpDoesNotUnprotectTheLastImageTurn() {
        var messages = List.of(
                userMessage("older", imagePart(4000)),
                userMessageWithImages("references", imagePart(4000), imagePart(4000)),
                Message.of(RoleType.USER, "use the second one"));

        var result = InlineImagePruner.prune(messages, 2, Long.MAX_VALUE);

        assertEquals(1, result.prunedCount());
        assertFalse(hasImagePart(result.messages().getFirst()));
        assertEquals(2, imageCount(result.messages().get(1)));
    }

    @Test
    void newestUserAttachmentSetSurvivesNewerToolImages() {
        var messages = List.of(
                userMessageWithImages("references", imagePart(4000), imagePart(4000), imagePart(4000)),
                toolMessage("read_file", "call-1", imagePart(4000)));

        var result = InlineImagePruner.prune(messages, 2, Long.MAX_VALUE);

        assertEquals(0, result.prunedCount());
        assertEquals(3, imageCount(result.messages().getFirst()));
        assertTrue(hasImagePart(result.messages().getLast()));
    }

    @Test
    void attachmentSetFallsBackToTheBudgetOnceTheConversationMovesOn() {
        var messages = List.of(
                userMessageWithImages("references", imagePart(4000), imagePart(4000), imagePart(4000)),
                Message.of(RoleType.USER, "first follow-up"),
                Message.of(RoleType.USER, "second follow-up"));

        var dropped = InlineImagePruner.prune(messages, 1, Long.MAX_VALUE);
        assertEquals(3, dropped.prunedCount());

        var kept = InlineImagePruner.prune(messages, 4, Long.MAX_VALUE);
        assertEquals(0, kept.prunedCount());
        assertEquals(3, imageCount(kept.messages().getFirst()));
    }

    @Test
    void toolImagesDoNotPiggybackOnTheUserTurnProtection() {
        var messages = List.of(
                userMessageWithImages("references", imagePart(4000), imagePart(4000)),
                toolMessage("read_file", "call-1", imagePart(4000)),
                toolMessage("read_file", "call-2", imagePart(4000)));

        var result = InlineImagePruner.prune(messages, 1, Long.MAX_VALUE);

        // the attachment set is exempt, the newest tool image is always kept, the older one is not
        assertEquals(1, result.prunedCount());
        assertEquals(2, imageCount(result.messages().getFirst()));
        assertFalse(hasImagePart(result.messages().get(1)));
        assertTrue(hasImagePart(result.messages().get(2)));
    }

    @Test
    void toolPlaceholderCarriesTheSourcePath() {
        var call = FunctionCall.of("call-1", "function", "read_file",
                "{\"file_path\":\"D:\\\\work\\\\render.png\"}");
        var assistant = Message.of(RoleType.ASSISTANT, null, null, null, List.of(call), "");
        var tool = Message.of(new Message.MessageRecord(RoleType.TOOL,
                List.of(Content.of("image loaded"), imagePart(4000)), null, "read_file", "call-1", null));

        var result = InlineImagePruner.prune(List.of(assistant, tool), 0, 0);

        assertTrue(result.messages().get(1).content.get(1).text.contains("D:\\work\\render.png"));
    }

    @Test
    void userPlaceholderNamesTheAttachmentAndDoesNotAskToCallTheUser() {
        var message = Message.of(new Message.MessageRecord(RoleType.USER,
                List.of(Content.of("[Image 1: poster.png]"), imagePart(4000)), null, "user", null, null));

        var result = InlineImagePruner.prune(List.of(message), 0, 0);

        var text = result.messages().getFirst().content.get(1).text;
        assertTrue(text.startsWith("[Image 1: poster.png]"));
        assertTrue(text.contains("Attach it again"));
        assertFalse(text.contains("Call user"));
    }

    private Message userMessage(String text, Content image) {
        return Message.of(new Message.MessageRecord(RoleType.USER, List.of(Content.of(text), image), null, null, null, null));
    }

    private Message userMessageWithImages(String text, Content... images) {
        var content = new java.util.ArrayList<Content>();
        content.add(Content.of(text));
        content.addAll(List.of(images));
        return Message.of(new Message.MessageRecord(RoleType.USER, content, null, null, null, null));
    }

    private Message toolMessage(String toolName, String toolCallId, Content image) {
        return Message.of(new Message.MessageRecord(RoleType.TOOL,
                List.of(Content.of("image loaded"), image), null, toolName, toolCallId, null));
    }

    private int imageCount(Message message) {
        return (int) message.content.stream().filter(part -> part.type == Content.ContentType.IMAGE_URL).count();
    }

    /** Distinct payloads per call: two images that share their bytes are the re-read case, not the default. */
    private Content imagePart(int base64Chars) {
        return imagePart(Character.toString('A' + IMAGE_SEQUENCE.getAndIncrement() % 26), base64Chars);
    }

    private Content imagePart(String filler, int base64Chars) {
        return Content.of(Content.ImageUrl.of("data:image/png;base64," + filler.repeat(base64Chars), "image/png"));
    }

    private boolean hasImagePart(Message message) {
        return message.content != null && message.content.stream().anyMatch(part -> part.type == Content.ContentType.IMAGE_URL);
    }
}
