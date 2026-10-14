package ai.core.cli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalUserMemoryTest {

    @TempDir
    Path tempDir;

    @Test
    void appendCreatesTheFileAndLoadReturnsTheStoredBullets() {
        var memory = new GlobalUserMemory(tempDir.resolve(".core-ai/MEMORY.md"));

        assertTrue(memory.load().isEmpty());
        assertTrue(memory.append("Always answer in Chinese."));
        assertFalse(memory.append("Prefer ripgrep over grep."));

        assertEquals("- Always answer in Chinese.\n- Prefer ripgrep over grep.", memory.load());
    }

    @Test
    void rejectsWritesThatExceedTheCap() {
        var memory = new GlobalUserMemory(tempDir.resolve(".core-ai/MEMORY.md"));
        memory.append("x".repeat(GlobalUserMemory.MAX_BYTES - 4));

        assertThrows(IllegalStateException.class, () -> memory.append("one more"));
    }

    @Test
    void loadTruncatesAnOversizedFile() throws IOException {
        Path dir = tempDir.resolve(".core-ai");
        Files.createDirectories(dir);
        Path file = dir.resolve("MEMORY.md");
        Files.writeString(file, "y".repeat(GlobalUserMemory.MAX_BYTES + 100));

        String loaded = new GlobalUserMemory(file).load();

        assertTrue(loaded.startsWith("y".repeat(GlobalUserMemory.MAX_BYTES)), loaded);
        assertTrue(loaded.endsWith("to trim)"), loaded);
    }
}
