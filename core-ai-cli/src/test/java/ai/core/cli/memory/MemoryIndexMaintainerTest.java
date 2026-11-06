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

class MemoryIndexMaintainerTest {

    @TempDir
    Path tempDir;

    private Path write(Path file, String content) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, content);
        return file;
    }

    @Test
    void addsMissingRowsAndDropsRowsForDeletedFiles() throws IOException {
        var workspace = tempDir.resolve("ws");
        var knowledge = workspace.resolve(".core-ai/knowledge");
        write(knowledge.resolve("project/agent-memory.md"), "# Server Agent Memory\n\nbody");
        write(knowledge.resolve("user/coding-style.md"), "# Coding Style\n\nbody");
        write(knowledge.resolve("MEMORY.md"), """
                # Knowledge Index

                ## Project

                | File | Description | When to Use | Created | Updated |
                |---|---|---|---|---|
                | project/agent-memory.md | Curated description | When changing memory | 2026-09-17 | 2026-09-17 |
                | project/deleted.md | Gone | - | 2026-09-17 | 2026-09-17 |

                ## User

                | File | Description | When to Use | Created | Updated |
                |---|---|---|---|---|

                ## Feedback

                | File | Description | When to Use | Created | Updated |
                |---|---|---|---|---|

                ## Reference

                | File | Description | When to Use | Created | Updated |
                |---|---|---|---|---|
                """);

        var result = MemoryIndexMaintainer.reconcile(workspace);
        String index = Files.readString(knowledge.resolve("MEMORY.md"));

        assertEquals(1, result.added(), "user/coding-style.md is new");
        assertEquals(1, result.removed(), "project/deleted.md no longer exists");
        assertTrue(index.contains("| project/agent-memory.md | Curated description | When changing memory |"), index);
        assertFalse(index.contains("project/deleted.md"), index);
        assertTrue(index.contains("user/coding-style.md"), index);
        assertTrue(index.contains("Coding Style"), index);
    }

    @Test
    void keepsTheNewestPageFirstWithinEachType() throws IOException {
        var workspace = tempDir.resolve("ws");
        var knowledge = workspace.resolve(".core-ai/knowledge");
        Path old = write(knowledge.resolve("project/old.md"), "# Old\n");
        Path fresh = write(knowledge.resolve("project/new.md"), "# New\n");
        Files.setLastModifiedTime(old, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(fresh, FileTime.from(Instant.parse("2026-11-01T00:00:00Z")));

        MemoryIndexMaintainer.reconcile(workspace);
        String index = Files.readString(knowledge.resolve("MEMORY.md"));

        assertTrue(index.indexOf("project/new.md") < index.indexOf("project/old.md"), index);
    }

    @Test
    void repairsARowWithAnUnescapedPipeInWhenToUse() throws IOException {
        var workspace = tempDir.resolve("ws");
        var knowledge = workspace.resolve(".core-ai/knowledge");
        write(knowledge.resolve("project/agent.md"), "# Agent\n");
        write(knowledge.resolve("MEMORY.md"), """
                # Knowledge Index

                ## Project

                | File | Description | When to Use | Created | Updated |
                |---|---|---|---|---|
                | project/agent.md | Agent CLI | Use `agent run | reply` | 2026-01-01 | 2026-01-01 |
                """);

        MemoryIndexMaintainer.reconcile(workspace);
        String index = Files.readString(knowledge.resolve("MEMORY.md"));

        assertTrue(index.contains("| project/agent.md | Agent CLI | Use `agent run \\| reply` | 2026-01-01 |"),
                index);
    }

    @Test
    void isIdempotent() throws IOException {
        var workspace = tempDir.resolve("ws");
        write(workspace.resolve(".core-ai/knowledge/project/page.md"), "# Page\n");

        var first = MemoryIndexMaintainer.reconcile(workspace);
        var second = MemoryIndexMaintainer.reconcile(workspace);

        assertTrue(first.changed());
        assertFalse(second.changed(), "a second pass must not rewrite the index");
        assertEquals(1, second.total());
    }
}
