package ai.core.agent.internal;

import ai.core.agent.AttachedContent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.media.FileSessionMediaStore;
import ai.core.media.MediaHydrator;
import ai.core.media.SessionMediaKey;
import ai.core.media.SessionMediaStore;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every producer hands the message build raw bytes; with a session store in the context the message
 * holds a ref and the bytes live in the store. Hydration is the only step that rebuilds a data URI.
 *
 * @author core-ai
 */
class AgentHelperMediaRefTest {
    // 1x1 transparent png
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
    private static final SessionMediaKey KEY = new SessionMediaKey("session-1", "user-1");

    private static ExecutionContext context(SessionMediaStore store) {
        return ExecutionContext.builder()
                .sessionId(KEY.sessionId())
                .userId(KEY.userId())
                .customVariable(SessionMediaStore.CONTEXT_KEY, store)
                .customVariable(SessionMediaStore.KEY_CONTEXT_KEY, KEY)
                .build();
    }

    private static Content imagePart(Message message) {
        return message.content.stream()
                .filter(part -> part.type == Content.ContentType.IMAGE_REF || part.type == Content.ContentType.IMAGE_URL)
                .findFirst()
                .orElseThrow();
    }

    @TempDir
    Path tempDir;

    @Test
    void anAttachmentBecomesARefAndHydratesBackToTheWireImage() {
        var store = new FileSessionMediaStore(tempDir);
        var context = context(store);
        context.setAttachedContents(List.of(AttachedContent.ofBase64(PNG, "image/png",
                AttachedContent.AttachedContentType.IMAGE, "photo.png")));

        var message = AgentHelper.buildUserMessage("what is this", context);

        var refPart = imagePart(message);
        assertEquals(Content.ContentType.IMAGE_REF, refPart.type);
        assertEquals("photo.png", refPart.imageRef.displayName);
        assertEquals(1, store.stats().objects());

        var wire = MediaHydrator.hydrate(List.of(message), store, KEY);
        var wirePart = imagePart(wire.getFirst());
        assertEquals(Content.ContentType.IMAGE_URL, wirePart.type);
        assertTrue(wirePart.imageUrl.url.startsWith("data:image/png;base64,"));
    }

    @Test
    void toolImageBecomesARefToo() {
        var store = new FileSessionMediaStore(tempDir);
        var context = context(store);
        var tool = FunctionCall.of("call-1", "function", "read_file", "{}");

        var message = AgentHelper.buildToolMessage(tool,
                ToolCallResult.completed("Image file read successfully").withImage(PNG, "image/png"), false, context);

        assertEquals(Content.ContentType.IMAGE_REF, imagePart(message).type);
        assertEquals(1, store.stats().objects());
    }
}
