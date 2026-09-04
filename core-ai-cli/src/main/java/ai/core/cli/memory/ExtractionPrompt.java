package ai.core.cli.memory;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

final class ExtractionPrompt {
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    private static final String TEMPLATE = "Run the knowledge extraction procedure.%n"
            + "The specification below is from `get_memory_extraction_spec` — already provided, no need to call.%n"
            + "%s%n%n"
            + "## Allowed Tools%n"
            + "Only use these tools — all others are forbidden for extraction:%n"
            + "- File: read_file, write_file, edit_file, glob_file, grep_file%n"
            + "- Knowledge log: add_knowledge_log%n"
            + "- Cursor: read_extraction_cursor, advance_extraction_cursor%n%n"
            + "When calling advance_extraction_cursor, pass the `cursor` parameter:%n"
            + "set it to the index you want the next extraction to start from%n"
            + "(i.e., the last message you processed + 1).%n%n"
            + "## Context%n"
            + "Workspace: %s%n"
            + "%s%n"
            + "Current datetime: %s%n"
            + "Max turns: %d%n";

    private static final String EXPLICIT_REQUEST_TEMPLATE = "%n## Explicit Memory Request%n"
            + "The user explicitly asked to remember the following. Store it in the knowledge wiki%n"
            + "even if the durability/self-check filters below would normally skip it — this explicit%n"
            + "request overrides them. Prioritize it over other candidates:%n"
            + "> %s%n%n"
            + "Extract it first, then continue the normal procedure for any remaining unprocessed messages.%n";

    static String format(Path workspace, String cursorInfo, ZonedDateTime now, int maxTurns, String explicitRequest) {
        String spec = MemoryExtractionTool.getCurrentSpec();
        String prompt = TEMPLATE.formatted(spec, workspace.toAbsolutePath(), cursorInfo, now.format(DATETIME_FMT), maxTurns);
        if (explicitRequest == null || explicitRequest.isBlank()) {
            return prompt;
        }
        return prompt + EXPLICIT_REQUEST_TEMPLATE.formatted(explicitRequest.strip());
    }

    private ExtractionPrompt() { }
}
