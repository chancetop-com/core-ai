package ai.core.cli.memory;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool for managing knowledge layer operation log (log.md).
 * Provides add_knowledge_log. Deletion is automatic (startup prune of entries older than 30 days).
 */
public class KnowledgeLogTool extends ToolCall {
    public static final String ADD_TOOL_NAME = "add_knowledge_log";

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeLogTool.class);
    private static final Pattern DATE_HEADER = Pattern.compile("^## \\[(\\d{4}-\\d{2}-\\d{2})]");
    private static final int RETENTION_DAYS = 30;

    /**
     * Remove log entries older than 30 days. Returns the number of entries pruned.
     */
    public static void pruneOldEntries(Path workspace) {
        var logFile = workspace.resolve(".core-ai/knowledge/log.md");
        if (!Files.isRegularFile(logFile)) return;

        try {
            var lines = Files.readAllLines(logFile);
            var cutoff = LocalDate.now().minusDays(RETENTION_DAYS);

            var sections = new ArrayList<String>();
            var currentSection = new StringBuilder();
            boolean currentIsOld = false;
            boolean hasCurrent = false;
            int totalSections = 0;

            for (String line : lines) {
                if (line.strip().startsWith("## [") && hasCurrent) {
                    if (!currentIsOld) sections.add(currentSection.toString());
                    totalSections++;
                    currentSection = new StringBuilder();
                    currentIsOld = false;
                    hasCurrent = false;
                }
                if (!hasCurrent) {
                    hasCurrent = true;
                    Matcher m = DATE_HEADER.matcher(line.strip());
                    if (m.find()) {
                        currentIsOld = LocalDate.parse(m.group(1), DateTimeFormatter.ISO_LOCAL_DATE)
                                .isBefore(cutoff);
                    }
                }
                currentSection.append(line).append('\n');
            }
            if (hasCurrent) {
                totalSections++;
                if (!currentIsOld) sections.add(currentSection.toString());
            }

            int pruned = totalSections - sections.size();
            if (pruned > 0) {
                Files.writeString(logFile, String.join("", sections));
                LOGGER.info("Pruned {} old entries from log.md ({} -> {} retained)",
                        pruned, totalSections, sections.size());
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to prune log.md: {}", e.getMessage());
        }
    }

    public static AddBuilder addBuilder() {
        return new AddBuilder();
    }

    private final Path knowledgeDir;

    public KnowledgeLogTool(Path workspace) {
        this.knowledgeDir = workspace.resolve(".core-ai/knowledge");
    }

    @Override
    public ToolCallResult execute(String text) {
        long startTime = System.currentTimeMillis();
        try {
            var argsMap = parseArguments(text);
            return executeAdd(argsMap, startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Failed to execute " + ADD_TOOL_NAME + ": " + e.getMessage(), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private ToolCallResult executeAdd(java.util.Map<String, Object> argsMap, long startTime) {
        String logInfo = getStringValue(argsMap, "log_info");
        if (logInfo == null || logInfo.isBlank()) {
            return ToolCallResult.failed("log_info is required")
                    .withDuration(System.currentTimeMillis() - startTime);
        }

        try {
            Files.createDirectories(knowledgeDir);
            Path logFile = knowledgeDir.resolve("log.md");
            String entry = "\n" + logInfo.strip() + "\n";
            Files.writeString(logFile, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return ToolCallResult.completed("Log entry added to " + logFile.toAbsolutePath())
                    .withDuration(System.currentTimeMillis() - startTime)
                    .withStats("logFile", logFile.toAbsolutePath().toString());
        } catch (IOException e) {
            return ToolCallResult.failed("Failed to write log: " + e.getMessage(), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    public static class AddBuilder extends ToolCall.Builder<AddBuilder, KnowledgeLogTool> {
        private Path workspace;

        public AddBuilder workspace(Path workspace) {
            this.workspace = workspace;
            return this;
        }

        @Override
        protected AddBuilder self() {
            return this;
        }

        public KnowledgeLogTool build() {
            this.name(ADD_TOOL_NAME);
            this.description("""
                    Append a log entry to .core-ai/knowledge/log.md.
                    log.md is a CHANGELOG for the knowledge layer — only record actual changes,
                    never execution details.
                    Rules:
                    1. ONLY call when wiki pages (project/, user/, feedback/, reference/) or
                       MEMORY.md index are actually added, updated, or deleted.
                    2. If nothing changed, SKIP — do NOT call this tool.
                    3. NEVER log execution details: cursor positions, message counts,
                       verification results, "nothing to extract", etc.
                    4. Do NOT log daily-logs or episodes changes — those are lower layers.
                    """);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "log_info",
                            "The log entry text recording actual knowledge-layer changes. "
                                    + "Format: '## [date] ingest|update | task description' header, then bullet list of Added/Updated/Deleted wiki pages and MEMORY.md. "
                                    + "Only record wiki page changes and MEMORY.md index updates. Do NOT record daily-logs, episodes, or execution details. "
                                    + "Example: '## [2026-03-28] ingest | grubhub menu crawler\\n- Added: reference/grubhub-api.md\\n- Updated: project/spa-scraping.md\\n- Updated: MEMORY.md'")
                            .required()
            ));
            var tool = new KnowledgeLogTool(workspace);
            build(tool);
            return tool;
        }
    }
}
