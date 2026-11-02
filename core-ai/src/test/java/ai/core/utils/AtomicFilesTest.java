package ai.core.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author stephen
 */
class AtomicFilesTest {
    @Test
    void failedWriteLeavesThePreviousTargetUntouchedAndNoTempFile(@TempDir Path dir) throws Exception {
        var target = dir.resolve("session.data");
        Files.writeString(target, "old snapshot");
        AtomicFiles.WRITER.set((path, content) -> {
            Files.write(path, Arrays.copyOf(content, content.length / 2));
            throw new IOException("simulated disk failure");
        });
        try {
            assertThrows(UncheckedIOException.class, () -> AtomicFiles.writeString(target, "new snapshot"));
        } finally {
            AtomicFiles.WRITER.remove();
        }
        assertEquals("old snapshot", Files.readString(target));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "the temporary file must be removed");
        }
    }

    @Test
    void successfulWriteReplacesTheTarget(@TempDir Path dir) throws Exception {
        var target = dir.resolve("session.data");
        Files.writeString(target, "old");
        AtomicFiles.writeString(target, "new");
        assertEquals("new", Files.readString(target));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }
}
