package ai.core.llm;

import ai.core.llm.domain.Content;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.media.MediaRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request budget must treat a media ref exactly like an inline image: it is bytes the provider
 * would receive, so an old ref is dropped to a placeholder while the newest user set survives.
 *
 * @author core-ai
 */
class InlineImagePrunerRefTest {
    @Test
    void prunesOlderRefsWithinTheCountBudget() {
        var messages = java.util.List.of(
                Message.of(RoleType.USER, Content.of(ref("a"))),
                Message.of(RoleType.USER, Content.of(ref("b"))),
                Message.of(RoleType.USER, Content.of(ref("c"))));

        var result = InlineImagePruner.prune(messages, 2, Long.MAX_VALUE);

        assertEquals(1, result.prunedCount());
        assertEquals(Content.ContentType.TEXT, result.messages().get(0).content.getFirst().type);
        assertTrue(result.messages().get(0).content.getFirst().text.contains("photo-a.png"));
        assertEquals(Content.ContentType.IMAGE_REF, result.messages().get(1).content.getFirst().type);
        assertEquals(Content.ContentType.IMAGE_REF, result.messages().get(2).content.getFirst().type);
    }

    @Test
    void dropsEveryRefForTheOversizeRetry() {
        var messages = java.util.List.of(
                Message.of(RoleType.USER, Content.of(ref("a"))),
                Message.of(RoleType.USER, Content.of(ref("b"))));

        var result = InlineImagePruner.prune(messages, 0, 0);

        assertEquals(2, result.prunedCount());
        assertFalse(result.messages().stream()
                .flatMap(message -> message.content.stream())
                .anyMatch(part -> part.type == Content.ContentType.IMAGE_REF));
    }

    @Test
    void keepsTheNewestUserSetEvenWhenItExceedsTheByteBudget() {
        var messages = java.util.List.of(
                Message.of(RoleType.USER, Content.of(ref("a"))),
                Message.of(RoleType.USER, Content.of(ref("b"))));

        var result = InlineImagePruner.prune(messages, 16, 1024);

        assertEquals(Content.ContentType.TEXT, result.messages().get(0).content.getFirst().type);
        assertEquals(Content.ContentType.IMAGE_REF, result.messages().get(1).content.getFirst().type);
    }

    private static MediaRef ref(String id) {
        return MediaRef.file(id, "C:/media/" + id + ".png", "image/png", "png", 2048, id)
                .displayName("photo-" + id + ".png")
                .source("attachment");
    }
}
