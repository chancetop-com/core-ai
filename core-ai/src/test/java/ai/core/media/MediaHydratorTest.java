package ai.core.media;

import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author core-ai
 */
class MediaHydratorTest {
    private static final SessionMediaKey KEY = new SessionMediaKey("session-1", "user-1");
    private static final MediaRef REF = MediaRef.file("sha-1", "C:/missing/photo.png", "image/png", "png", 3, "sha-1")
            .displayName("photo.png")
            .source("attachment");

    @Test
    void replacesARefWithTheWireDataUriAndLeavesTheInputUntouched() {
        var message = Message.of(RoleType.USER, Content.of(REF));
        var request = CompletionRequest.of(List.of(message), null, null, "model", null);
        request.setMediaStore(store(true));
        request.setMediaKey(KEY);

        var wire = MediaHydrator.hydrate(request);

        var part = wire.messages.getFirst().content.getFirst();
        assertEquals(Content.ContentType.IMAGE_URL, part.type);
        assertEquals("data:image/png;base64,AQID", part.imageUrl.url);
        assertEquals(Content.ContentType.IMAGE_REF, message.content.getFirst().type);
    }

    @Test
    void degradesAMissingObjectToATextPlaceholder() {
        var request = CompletionRequest.of(List.of(Message.of(RoleType.USER, Content.of(REF))), null, null, "model", null);
        request.setMediaStore(store(false));
        request.setMediaKey(KEY);

        var wire = MediaHydrator.hydrate(request);

        var part = wire.messages.getFirst().content.getFirst();
        assertEquals(Content.ContentType.TEXT, part.type);
        assertTrue(part.text.contains("photo.png"));
    }

    @Test
    void refusesToEncodeAnUnhydratedRef() {
        var messages = List.of(Message.of(RoleType.USER, Content.of(REF)));
        assertThrows(IllegalStateException.class, () -> MediaHydrator.assertNoUnhydratedRefs(messages));
    }

    private static SessionMediaStore store(boolean present) {
        return new SessionMediaStore() {
            @Override
            public MediaRef put(SessionMediaKey key, byte[] bytes, String mediaType, String displayName, String source) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MediaRef register(SessionMediaKey key, MediaRef existing) {
                return existing;
            }

            @Override
            public Optional<MediaRef> find(SessionMediaKey key, String mediaId) {
                return Optional.empty();
            }

            @Override
            public Optional<byte[]> read(SessionMediaKey key, MediaRef ref) {
                return present ? Optional.of(new byte[]{1, 2, 3}) : Optional.empty();
            }

            @Override
            public void deleteSession(String sessionId) {
            }

            @Override
            public MediaStoreStats stats() {
                return MediaStoreStats.EMPTY;
            }
        };
    }
}
