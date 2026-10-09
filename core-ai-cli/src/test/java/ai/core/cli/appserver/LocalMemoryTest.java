package ai.core.cli.appserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class LocalMemoryTest {
    @TempDir
    Path workspace;

    @Test
    void listsAndReadsKnowledgeFiles() throws Exception {
        var knowledge = workspace.resolve(".core-ai/knowledge");
        Files.createDirectories(knowledge.resolve("user"));
        Files.writeString(knowledge.resolve("MEMORY.md"), "# index");
        Files.writeString(knowledge.resolve("user/coding-style.md"), "rules");

        var listed = LocalMemory.list(workspace, Params.object());
        var paths = new ArrayList<String>();
        listed.get("files").forEach(node -> paths.add(node.get("path").asText()));
        assertTrue(paths.contains("knowledge/MEMORY.md"));
        assertTrue(paths.contains("knowledge/user/coding-style.md"));

        var params = Params.object().put("path", "knowledge/MEMORY.md");
        var read = LocalMemory.read(workspace, params);
        assertEquals("# index", read.get("content").asText());
    }

    @Test
    void paginatesNewestFirst() throws Exception {
        var episodes = workspace.resolve(".core-ai/episodes");
        Files.createDirectories(episodes);
        Files.writeString(episodes.resolve("2026-10-01.md"), "a");
        Files.writeString(episodes.resolve("2026-10-02.md"), "b");
        Files.writeString(episodes.resolve("2026-10-03.md"), "c");

        var first = LocalMemory.list(workspace, Params.object().put("limit", 2));
        assertEquals(2, first.get("files").size());
        assertEquals("2", first.get("nextCursor").asText());
        var second = LocalMemory.list(workspace, Params.object().put("limit", 2).put("cursor", "2"));
        assertEquals(1, second.get("files").size());
    }

    @Test
    void rejectsPathEscapes() {
        var error = assertThrows(RpcException.class,
                () -> LocalMemory.read(workspace, Params.object().put("path", "../secret.txt")));
        assertEquals("PATH_NOT_ALLOWED", error.data().path("code").asText());
    }

    @Test
    void reportsMissingFiles() {
        var error = assertThrows(RpcException.class,
                () -> LocalMemory.read(workspace, Params.object().put("path", "knowledge/nope.md")));
        assertEquals("FILE_NOT_FOUND", error.data().path("code").asText());
    }

    @Test
    void rejectsOversizedFiles() throws Exception {
        var knowledge = workspace.resolve(".core-ai/knowledge");
        Files.createDirectories(knowledge);
        Files.writeString(knowledge.resolve("big.md"), "x".repeat(300 * 1024));
        var error = assertThrows(RpcException.class,
                () -> LocalMemory.read(workspace, Params.object().put("path", "knowledge/big.md")));
        assertEquals("FILE_TOO_LARGE", error.data().path("code").asText());
    }
}
