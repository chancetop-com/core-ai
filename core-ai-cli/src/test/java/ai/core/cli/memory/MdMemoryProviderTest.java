package ai.core.cli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

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

    @Test
    void recentEpisodesSurviveALargeGlobalMemoryAndIndex() throws IOException {
        var workspace = tempDir.resolve("ws");
        write(workspace.resolve(".core-ai/knowledge/MEMORY.md"), "i".repeat(3 * 1024));
        write(workspace.resolve(".core-ai/episodes/2026-11-06.md"), "latest episode content");
        var global = new GlobalUserMemory(write(tempDir.resolve("home/.core-ai/MEMORY.md"), "g".repeat(2 * 1024)));

        String content = new MdMemoryProvider(workspace, global).load();

        assertTrue(content.contains("latest episode content"), content);
    }

    @Test
    void loadsTheNewestUserPagesFirst() throws IOException {
        var workspace = tempDir.resolve("ws");
        Path old = write(workspace.resolve(".core-ai/knowledge/user/old.md"), "old preference");
        Path fresh = write(workspace.resolve(".core-ai/knowledge/user/new.md"), "new preference");
        Files.setLastModifiedTime(old, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(fresh, FileTime.from(Instant.parse("2026-11-01T00:00:00Z")));

        String content = new MdMemoryProvider(workspace, new GlobalUserMemory(tempDir.resolve("absent/MEMORY.md")))
                .load();

        assertTrue(content.indexOf("new preference") < content.indexOf("old preference"), content);
    }

    @Test
    void compactIndexKeepsEveryTypeVisible() throws IOException {
        var workspace = tempDir.resolve("ws");
        var index = new StringBuilder(4096);
        index.append("# Knowledge Index\n\n## Project\n\n"
                + "| File | Description | When to Use | Created | Updated |\n|---|---|---|---|---|\n");
        for (int i = 0; i < 60; i++) {
            index.append("| project/p").append(i).append(".md | description ").append(i)
                    .append(" | - | 2026-01-01 | 2026-01-01 |\n");
        }
        index.append("\n## User\n\n| File | Description | When to Use | Created | Updated |\n|---|---|---|---|---|\n");
        for (int i = 0; i < 5; i++) {
            index.append("| user/u").append(i).append(".md | user ").append(i)
                    .append(" | - | 2026-01-01 | 2026-01-01 |\n");
        }
        write(workspace.resolve(".core-ai/knowledge/MEMORY.md"), index.toString());

        String content = new MdMemoryProvider(workspace, new GlobalUserMemory(tempDir.resolve("absent/MEMORY.md")))
                .load();

        assertTrue(content.contains("## Project"), content);
        assertTrue(content.contains("## User"), content);
        assertTrue(content.contains("project/p0.md"), "the newest project rows survive");
        assertFalse(content.contains("project/p59.md"), "the oldest project rows are dropped");
        assertTrue(content.contains("more rows"), content);
    }

    @Test
    void listsRecentKnowledgePagesNewestFirst() throws IOException {
        var workspace = tempDir.resolve("ws");
        Path old = write(workspace.resolve(".core-ai/knowledge/project/old.md"),
                "---\ndescription: old page\n---\nold body");
        Path fresh = write(workspace.resolve(".core-ai/knowledge/project/new.md"),
                "---\ndescription: new page\n---\nnew body");
        Files.setLastModifiedTime(old, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(fresh, FileTime.from(Instant.parse("2026-11-01T00:00:00Z")));

        String content = new MdMemoryProvider(workspace, new GlobalUserMemory(tempDir.resolve("absent/MEMORY.md")))
                .load();

        assertTrue(content.contains("--- Recent Knowledge Pages (newest first) ---"), content);
        assertTrue(content.indexOf("project/new.md") < content.indexOf("project/old.md"), content);
        assertTrue(content.contains("new page"), content);
    }
}
