package ai.core.cli.memory;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

final class LockProcessingPrompt {
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    private static final String TEMPLATE = """
            When a session closes, the session close agent creates a **lock file** listing
            daily-logs that need deep knowledge extraction. The lock file is a work queue —
            process each listed daily-log, extract knowledge into wiki pages, update episodes,
            then **delete the lock file**.

            File formats and knowledge types are defined in your system prompt.

            ## Allowed Tools
            Only use these tools — all others are forbidden for extraction:
            - File: read_file, write_file, edit_file, glob_file, grep_file
            - Knowledge log: add_knowledge_log

            ## Steps

            1. For each daily-log listed below, read it, extract durable knowledge into wiki pages.
            2. Update `.core-ai/episodes/{date}.md` — add an index entry for each processed daily-log.
            3. Call `add_knowledge_log` to record knowledge-layer changes only
            (wiki pages, MEMORY.md — do NOT log daily-logs or episodes).
            4. Delete the lock file.

            ## Lock File

            Lock file path: %s
            Delete this file after all extraction is complete.

            Daily-logs to extract:

            ```
            %s
            ```

            Workspace: %s
            Current datetime: %s
            Max turns: %d
            """;

    static String format(Path lockFile, String lockContent, Path workspace,
                         ZonedDateTime now, int maxTurns) {
        return TEMPLATE.formatted(lockFile.toAbsolutePath(), lockContent,
                workspace.toAbsolutePath(), now.format(DATETIME_FMT), maxTurns);
    }

    private LockProcessingPrompt() { }
}
