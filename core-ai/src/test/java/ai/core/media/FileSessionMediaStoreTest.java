package ai.core.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author core-ai
 */
class FileSessionMediaStoreTest {
    private static final SessionMediaKey KEY = new SessionMediaKey("session-1", "user-1");

    @TempDir
    Path tempDir;

    @Test
    void storesReadsAndDeduplicatesByContent() {
        var store = new FileSessionMediaStore(tempDir);
        var bytes = new byte[]{1, 2, 3, 4};

        var first = store.put(KEY, bytes, "image/png", "photo.png", "attachment");
        var second = store.put(KEY, bytes.clone(), "image/png", "photo.png", "attachment");

        assertEquals(first.id, second.id);
        assertEquals(first.sha256, second.sha256);
        assertTrue(Path.of(first.path).startsWith(tempDir.resolve("media").toAbsolutePath()));
        assertArrayEquals(bytes, store.read(KEY, first).orElseThrow());
        assertEquals(1, store.stats().objects());
        assertEquals(4, store.stats().bytes());
    }

    @Test
    void rejectsAnImageOverThePerObjectBudget() {
        var store = new FileSessionMediaStore(tempDir);
        var tooLarge = new byte[(int) MediaStoreLimits.MAX_IMAGE_BYTES + 1];
        assertThrows(MediaLimitExceededException.class,
                () -> store.put(KEY, tooLarge, "image/png", "huge.png", "attachment"));
    }

    @Test
    void refusesToReadOutsideTheSessionDirectory() {
        var store = new FileSessionMediaStore(tempDir);
        var outside = tempDir.resolveSibling("outside.png").toAbsolutePath();
        var ref = MediaRef.file("outside", outside.toString(), "image/png", "png", 4, "sha")
                .displayName("outside.png")
                .source("test");

        assertTrue(store.read(KEY, ref).isEmpty());
    }

    @Test
    void deletesEveryObjectOfTheSession() {
        var store = new FileSessionMediaStore(tempDir);
        store.put(KEY, new byte[]{1}, "image/png", "a.png", "attachment");

        store.deleteSession("session-1");

        assertEquals(0, store.stats().objects());
        try (var files = Files.list(tempDir.resolve("media"))) {
            assertEquals(0, files.count());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
