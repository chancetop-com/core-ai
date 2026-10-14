package ai.core.cli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveUserMemoryToolTest {

    @TempDir
    Path tempDir;

    @Test
    void appendsThePreferenceAndReportsTheStorePath() {
        var memory = new GlobalUserMemory(tempDir.resolve("MEMORY.md"));
        var tool = SaveUserMemoryTool.builder().globalMemory(memory).build();

        var result = tool.execute("{\"content\":\"Always answer in Chinese.\"}");

        assertFalse(result.isFailed());
        assertEquals("- Always answer in Chinese.", memory.load());
        assertEquals(memory.path().toAbsolutePath().toString(), result.getStats().get("path"));
        assertEquals(Boolean.TRUE, result.getStats().get("created"));
    }

    @Test
    void reportsUpdatedWhenTheStoreAlreadyExists() {
        var memory = new GlobalUserMemory(tempDir.resolve("MEMORY.md"));
        memory.append("Existing preference.");
        var tool = SaveUserMemoryTool.builder().globalMemory(memory).build();

        var result = tool.execute("{\"content\":\"Another preference.\"}");

        assertFalse(result.isFailed());
        assertEquals(Boolean.FALSE, result.getStats().get("created"));
    }

    @Test
    void rejectsBlankContent() {
        var tool = SaveUserMemoryTool.builder()
                .globalMemory(new GlobalUserMemory(tempDir.resolve("MEMORY.md")))
                .build();

        assertTrue(tool.execute("{\"content\":\"   \"}").isFailed());
    }
}
