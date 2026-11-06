package ai.core.cli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void appendDropsTheOldestEntriesInsteadOfRejectingNewOnes() throws IOException {
        var memory = new GlobalUserMemory(tempDir.resolve(".core-ai/MEMORY.md"));
        memory.append("oldest");
        memory.append("x".repeat(GlobalUserMemory.MAX_CHARS - 20));
        memory.append("newest");

        String loaded = memory.load();

        assertTrue(loaded.endsWith("- newest"), loaded);
        assertFalse(loaded.contains("oldest"), loaded);
        assertTrue(loaded.length() <= GlobalUserMemory.MAX_CHARS, "the store stays bounded");
        assertTrue(Files.readString(tempDir.resolve(".core-ai/" + GlobalUserMemory.ARCHIVE_FILE_NAME))
                .contains("- oldest"), "trimmed entries are archived, not lost");
    }

    @Test
    void loadKeepsTheNewestEntriesWhenTheFileIsOversized() throws IOException {
        Path dir = tempDir.resolve(".core-ai");
        Files.createDirectories(dir);
        Path file = dir.resolve("MEMORY.md");
        Files.writeString(file, "- oldest\n" + "y".repeat(GlobalUserMemory.MAX_CHARS) + "\n- newest");

        String loaded = new GlobalUserMemory(file).load();

        assertTrue(loaded.endsWith("- newest"), loaded);
        assertFalse(loaded.contains("- oldest"), loaded);
        assertTrue(loaded.startsWith("(older entries trimmed"), loaded);
    }
}
