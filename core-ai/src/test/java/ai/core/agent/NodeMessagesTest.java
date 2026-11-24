package ai.core.agent;

import ai.core.llm.domain.Content;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * History is the display projection of a session: it must not keep the working messages (and their inline
 * images) alive, and undo must still find its entry after that snapshot copy.
 *
 * @author stephen
 */
class NodeMessagesTest {
    @Test
    void historyStoresTextSnapshotInsteadOfTheMessage() {
        var messages = new NodeMessages();
        var message = userMessage("look at this", imagePart());

        messages.add(message);
        messages.recordHistory(message);

        var snapshot = messages.history().getFirst();
        assertNotSame(message, snapshot);
        assertTrue(snapshot.content.stream().noneMatch(part -> part.type == Content.ContentType.IMAGE_URL));
        assertFalse(JsonUtil.toJson(messages.history()).contains("data:"));
        assertEquals("look at this", snapshot.getTextContent());
    }

    @Test
    void undoRemovesTheNewestMatchingHistoryEntry() {
        var messages = new NodeMessages();
        addConversation(messages, "q1", "a1", "q2", "a2");
        var removed = List.of(messages.messages().get(2), messages.messages().get(3));

        messages.messages().subList(2, 4).clear();
        for (var message : removed) {
            messages.removeHistory(message);
        }

        assertEquals(2, messages.history().size());
        assertEquals(List.of("q1", "a1"), messages.history().stream().map(Message::getTextContent).toList());
    }

    @Test
    void undoRemovesRepeatedTextFromTheTail() {
        var messages = new NodeMessages();
        addConversation(messages, "repeat", "ok", "repeat");
        var lastUser = messages.messages().getLast();

        messages.remove(lastUser);

        assertEquals(2, messages.history().size());
        assertEquals(List.of("repeat", "ok"), messages.history().stream().map(Message::getTextContent).toList());
    }

    @Test
    void restoreRebuildsLegacyHistoryWithoutInlineMedia() {
        var messages = new NodeMessages();
        var legacy = userMessage("look at this", imagePart());

        messages.restore(List.of(legacy), List.of(legacy));

        assertTrue(messages.messages().getFirst().content.stream()
                .anyMatch(part -> part.type == Content.ContentType.IMAGE_URL));
        var snapshot = messages.history().getFirst();
        assertNotSame(legacy, snapshot);
        assertEquals("look at this", snapshot.getTextContent());
        assertFalse(JsonUtil.toJson(messages.history()).contains("data:"));
    }

    @Test
    void restoreStripsEmbeddedDataUriText() {
        var messages = new NodeMessages();
        var legacy = Message.of(RoleType.USER, "see ![x](data:image/png;base64," + "A".repeat(512) + ")");

        messages.restore(List.of(legacy), List.of(legacy));

        var text = messages.history().getFirst().getTextContent();
        assertFalse(text.contains("data:"));
        assertTrue(text.contains("[inline image omitted]"));
    }

    private void addConversation(NodeMessages messages, String... texts) {
        for (var text : texts) {
            var role = messages.messages().size() % 2 == 0 ? RoleType.USER : RoleType.ASSISTANT;
            var message = Message.of(role, text);
            messages.add(message);
            messages.recordHistory(message);
        }
    }

    private Message userMessage(String text, Content image) {
        return Message.of(new Message.MessageRecord(RoleType.USER,
                List.of(Content.of(text), image), null, null, null, null));
    }

    private Content imagePart() {
        return Content.of(Content.ImageUrl.of("data:image/png;base64," + "A".repeat(4096), "image/png"));
    }
}
