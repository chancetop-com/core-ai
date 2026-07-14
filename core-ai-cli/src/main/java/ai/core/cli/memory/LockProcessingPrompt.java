package ai.core.cli.memory;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

final class LockProcessingPrompt {
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    private static final String TEMPLATE = "When a session closes, the session close agent creates a **lock file** listing%n"
            + "daily-logs that need deep knowledge extraction. The lock file is a work queue —%n"
            + "process each listed daily-log, extract knowledge into wiki pages, update episodes,%n"
            + "then **delete the lock file**.%n"
            + MemoryExtractionSpecs.EXTRACTION_SPEC + "%n"
            + "## Allowed Tools%n"
            + "Only use these tools — all others are forbidden for extraction:%n"
            + "- File: read_file, write_file, edit_file, glob_file, grep_file%n"
            + "- Knowledge log: add_knowledge_log%n%n"
            + "## Steps%n%n"
            + "1. For each daily-log listed below, read it, extract durable knowledge into wiki pages.%n"
            + "2. Update `.core-ai/episodes/{date}.md` — add an index entry for each processed daily-log.%n"
            + "3. Call `add_knowledge_log` to record knowledge-layer changes only%n"
            + "(wiki pages, MEMORY.md — do NOT log daily-logs or episodes).%n"
            + "4. Delete the lock file.%n%n"
            + "## Lock File%n%n"
            + "Lock file path: %s%n"
            + "Delete this file after all extraction is complete.%n%n"
            + "Daily-logs to extract:%n%n"
            + "```%n"
            + "%s%n"
            + "```%n%n"
            + "Workspace: %s%n"
            + "Current datetime: %s%n"
            + "Max turns: %d%n";

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("VA_FORMAT_STRING_USES_NEWLINE")
    static String format(Path lockFile, String lockContent, Path workspace,
                         ZonedDateTime now, int maxTurns) {
        return TEMPLATE.formatted(lockFile.toAbsolutePath(), lockContent,
                workspace.toAbsolutePath(), now.format(DATETIME_FMT), maxTurns);
    }

    private LockProcessingPrompt() { }
}
