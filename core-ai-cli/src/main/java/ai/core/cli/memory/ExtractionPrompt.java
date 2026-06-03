package ai.core.cli.memory;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

final class ExtractionPrompt {
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    private static final String TEMPLATE = """
            Run the knowledge extraction procedure.
            The specification below is from `get_memory_extraction_spec` — already provided, no need to call.
            %s

            ## Allowed Tools
            Only use these tools — all others are forbidden for extraction:
            - File: read_file, write_file, edit_file, glob_file, grep_file
            - Knowledge log: add_knowledge_log
            - Cursor: read_extraction_cursor, advance_extraction_cursor

            When calling advance_extraction_cursor, pass the `cursor` parameter:
            set it to the index you want the next extraction to start from
            (i.e., the last message you processed + 1).

            ## Context
            Workspace: %s
            %s
            Current datetime: %s
            Max turns: %d
            """;

    static String format(Path workspace, String cursorInfo, ZonedDateTime now, int maxTurns) {
        String spec = MemoryExtractionTool.getCurrentSpec();
        return TEMPLATE.formatted(spec, workspace.toAbsolutePath(), cursorInfo, now.format(DATETIME_FMT), maxTurns);
    }

    private ExtractionPrompt() { }
}
