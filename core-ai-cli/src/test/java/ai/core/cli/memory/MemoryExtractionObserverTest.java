package ai.core.cli.memory;

import ai.core.llm.domain.FunctionCall;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryExtractionObserverTest {

    private static FunctionCall call(String id, String name, Map<String, Object> arguments) {
        return FunctionCall.of(id, "function", name, JsonUtil.toJson(arguments));
    }

    @TempDir
    Path tempDir;

    @Test
    void recordsNewPagesAsAddedAndExistingPagesAsUpdated() throws IOException {
        var observer = new MemoryExtractionObserver();
        Path existing = tempDir.resolve(".core-ai/knowledge/project/architecture.md");
        Files.createDirectories(tempDir.resolve(".core-ai/knowledge/project"));
        Files.createFile(existing);

        FunctionCall created = call("c1", "write_file", Map.of("file_path",
                tempDir.resolve(".core-ai/knowledge/reference/desktop-memory.md").toString(), "content", "x"));
        observer.beforeTool(created, null);
        observer.afterTool(created, null, ToolCallResult.completed("ok"));

        FunctionCall edited = call("c2", "edit_file", Map.of("file_path", existing.toString(), "old_string", "a"));
        observer.beforeTool(edited, null);
        observer.afterTool(edited, null, ToolCallResult.completed("ok"));

        assertEquals(List.of(".core-ai/knowledge/reference/desktop-memory.md"), observer.added());
        assertEquals(List.of(".core-ai/knowledge/project/architecture.md"), observer.updated());
    }

    @Test
    void keepsAPageAddedWhenTheSameRunAlsoEditsIt() throws IOException {
        var observer = new MemoryExtractionObserver();
        Path page = tempDir.resolve(".core-ai/knowledge/user/preferences.md");

        FunctionCall created = call("c1", "write_file", Map.of("file_path", page.toString(), "content", "x"));
        observer.beforeTool(created, null);
        observer.afterTool(created, null, ToolCallResult.completed("ok"));

        Files.createDirectories(tempDir.resolve(".core-ai/knowledge/user"));
        Files.createFile(page);
        FunctionCall edited = call("c2", "edit_file", Map.of("file_path", page.toString(), "old_string", "a"));
        observer.beforeTool(edited, null);
        observer.afterTool(edited, null, ToolCallResult.completed("ok"));

        assertEquals(List.of(".core-ai/knowledge/user/preferences.md"), observer.added());
        assertTrue(observer.updated().isEmpty());
    }

    @Test
    void ignoresWritesOutsideTheMemoryStoreAndFailedWrites() {
        var observer = new MemoryExtractionObserver();

        FunctionCall taskOutput = call("c1", "write_file", Map.of("file_path",
                tempDir.resolve(".core-ai/tasks/report.md").toString(), "content", "x"));
        observer.beforeTool(taskOutput, null);
        observer.afterTool(taskOutput, null, ToolCallResult.completed("ok"));

        FunctionCall failed = call("c2", "write_file", Map.of("file_path",
                tempDir.resolve(".core-ai/knowledge/project/lost.md").toString(), "content", "x"));
        observer.beforeTool(failed, null);
        observer.afterTool(failed, null, ToolCallResult.failed("disk full"));

        assertTrue(observer.added().isEmpty());
        assertTrue(observer.updated().isEmpty());
    }

    @Test
    void recordsKnowledgeLogNoteAndAdvancedCursor() {
        var observer = new MemoryExtractionObserver();
        String log = "## [2026-10-10] ingest | desktop memory chip\n- Added: reference/desktop-memory.md";

        FunctionCall logCall = call("c1", "add_knowledge_log", Map.of("log_info", log));
        observer.afterTool(logCall, null, ToolCallResult.completed("ok"));

        FunctionCall advance = call("c2", "advance_extraction_cursor", Map.of("cursor", 42));
        observer.afterTool(advance, null, ToolCallResult.completed("ok").withStats("cursor", 42));

        assertEquals(log, observer.note());
        assertEquals(42, observer.cursor());
    }

    @Test
    void reportsNoNoteWhenNothingWasLogged() {
        var observer = new MemoryExtractionObserver();

        assertNull(observer.note());
        assertEquals(-1, observer.cursor());
    }

    @Test
    void resolvesMemoryPathsFromAbsoluteAndRelativeForms() {
        assertEquals(".core-ai/knowledge/MEMORY.md", MemoryExtractionObserver.memoryRelativePath(
                "D:/work/core-ai/.core-ai/knowledge/MEMORY.md"));
        assertEquals(".core-ai/knowledge/project/x.md", MemoryExtractionObserver.memoryRelativePath(
                ".core-ai/knowledge/project/x.md"));
        assertEquals(".core-ai/daily-logs/2026-10-10.md", MemoryExtractionObserver.memoryRelativePath(
                "c:\\work\\.core-ai\\daily-logs\\2026-10-10.md"));
        assertNull(MemoryExtractionObserver.memoryRelativePath("src/main/java/Foo.java"));
        assertNull(MemoryExtractionObserver.memoryRelativePath(".core-ai/tasks/report.md"));
    }

    @Test
    void skipsWorkspacePathsThatThemselvesContainADotCoreAiDirectory() {
        assertEquals(".core-ai/knowledge/reference/x.md", MemoryExtractionObserver.memoryRelativePath(
                "D:/core-ai/.core-ai/tasks/memory-probe-ws/.core-ai/knowledge/reference/x.md"));
        assertEquals(".core-ai/knowledge/MEMORY.md", MemoryExtractionObserver.memoryRelativePath(
                "C:/Users/u/.core-ai/workspaces/2026-10-10/.core-ai/knowledge/MEMORY.md"));
    }
}
