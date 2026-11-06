package ai.core.cli.memory;

import java.nio.file.Path;

/**
 * Prompt for the isolated memory-curator agent. The curator works on the knowledge wiki only; the
 * host rebuilds {@code MEMORY.md} deterministically after it finishes, so it must not edit the index.
 */
public final class MemoryCuratorPrompt {

    private static final String WORKSPACE = "{{workspace}}";

    private static final String TEMPLATE = """
            You are the memory curator for this workspace. Work only inside:
              {{workspace}}/.core-ai/knowledge
            Do not touch code, docs, sessions, or any file outside that directory.

            Goal: keep the knowledge wiki small, current and non-duplicated.

            Rules:
            1. Read MEMORY.md only to see the catalog, then read the pages that look duplicated or stale.
            2. Merge pages that cover the same topic into one page; keep the file name the index uses.
            3. When two statements conflict, keep the newest verified one and remove the outdated one.
            4. Do not invent facts, do not generalize from one incident, and do not rewrite user preferences.
            5. Prefer edit_file over write_file. Delete a page only when its content is entirely duplicated
               elsewhere or clearly obsolete. Never delete a page under user/ or feedback/ unless it is an
               exact duplicate.
            6. Keep each page focused; if a page exceeds roughly 300 lines, split it by topic.
            7. After changes, call add_knowledge_log once with a summary.
            8. Do not edit MEMORY.md by hand; the host rebuilds it from the files on disk after you finish.

            Finish with one short paragraph describing what you merged, updated, or removed.
            """;

    public static String format(Path workspace) {
        return TEMPLATE.replace(WORKSPACE, workspace.toAbsolutePath().toString());
    }

    private MemoryCuratorPrompt() {
    }
}
