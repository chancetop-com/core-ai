package ai.core.cli.memory;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

final class ExtractionPrompt {
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    private static final String TEMPLATE = """
            Run the knowledge extraction procedure defined in your system prompt.

            ## Allowed Tools
            Only use these tools — all others are forbidden for extraction:
            - File: read_file, write_file, edit_file, glob_file, grep_file
            - Knowledge log: add_knowledge_log
            - Cursor: read_extraction_cursor, advance_extraction_cursor

            When calling advance_extraction_cursor, pass the `cursor` parameter:
            set it to the index you want the next extraction to start from
            (i.e., the last message you processed + 1).

            ## Task Outcome Reconciliation

            After each daily-log, reconcile the task outcome with existing wiki knowledge.
            Success: explicit approval, verifier output used to complete, no user objection.

            For successful tasks:
            1. Identify approach — compare with wiki: does any page claim it's impossible?
            2. Mark conflicts — add `⚠ Correction` to wiki via edit_file (round, date, approach).
            3. Delete after 2+ — same claim contradicted by 2+ completions → delete old, replace.

            Also: if conversation reveals a wiki page is inaccurate, update via edit_file.
            Do NOT scan the codebase beyond the conversation.

            ## Knowledge Log
            Record knowledge-layer changes only via add_knowledge_log
            (wiki pages, MEMORY.md — do NOT log daily-logs or episodes).

            ## Context
            Workspace: %s
            %s
            Current datetime: %s
            Max turns: %d
            """;

    static String format(Path workspace, String cursorInfo, ZonedDateTime now, int maxTurns) {
        return TEMPLATE.formatted(workspace.toAbsolutePath(), cursorInfo, now.format(DATETIME_FMT), maxTurns);
    }

    private ExtractionPrompt() { }
}
