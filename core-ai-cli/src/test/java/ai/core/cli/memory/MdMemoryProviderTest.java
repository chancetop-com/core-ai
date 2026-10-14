package ai.core.cli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MdMemoryProviderTest {

    @TempDir
    Path tempDir;

    private Path write(Path file, String content) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, content);
        return file;
    }

    @Test
    void loadPutsTheGlobalMemoryAheadOfTheWorkspaceIndex() throws IOException {
        var workspace = tempDir.resolve("ws");
        write(workspace.resolve(".core-ai/knowledge/MEMORY.md"), "| File | Description |");
        var global = new GlobalUserMemory(write(tempDir.resolve("home/.core-ai/MEMORY.md"),
                "- Always answer in Chinese."));

        String content = new MdMemoryProvider(workspace, global).load();

        assertTrue(content.startsWith(GlobalUserMemory.HEADER), content);
        assertTrue(content.contains("- Always answer in Chinese."), content);
        assertTrue(content.indexOf(GlobalUserMemory.HEADER) < content.indexOf("--- Memory Index ---"), content);
    }

    @Test
    void loadWithoutGlobalMemoryKeepsTheWorkspaceLayout() throws IOException {
        var workspace = tempDir.resolve("ws");
        write(workspace.resolve(".core-ai/knowledge/MEMORY.md"), "| File | Description |");

        String content = new MdMemoryProvider(workspace, new GlobalUserMemory(tempDir.resolve("absent/MEMORY.md"))).load();

        assertFalse(content.contains(GlobalUserMemory.HEADER), content);
        assertTrue(content.startsWith("--- Memory Index ---"), content);
    }

    @Test
    void listMemoriesExposesTheGlobalEntryWhenItHasContent() throws IOException {
        var workspace = tempDir.resolve("ws");
        var global = new GlobalUserMemory(write(tempDir.resolve("home/.core-ai/MEMORY.md"),
                "- Always answer in Chinese."));

        var entries = new MdMemoryProvider(workspace, global).listMemories();

        assertEquals(1, entries.size());
        assertEquals("global/MEMORY.md", entries.getFirst().fileName());
        assertEquals("global", entries.getFirst().type());
        assertTrue(entries.getFirst().matches("chinese"));
    }

    @Test
    void listMemoriesSkipsAnEmptyGlobalStore() {
        var workspace = tempDir.resolve("ws");

        var entries = new MdMemoryProvider(workspace, new GlobalUserMemory(tempDir.resolve("absent/MEMORY.md")))
                .listMemories();

        assertTrue(entries.isEmpty());
    }
}
