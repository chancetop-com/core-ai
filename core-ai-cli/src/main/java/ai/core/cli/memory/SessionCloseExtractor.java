package ai.core.cli.memory;

import ai.core.agent.Agent;
import ai.core.cli.utils.AgentFork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * author cyril
 * description
 * createTime  2026/5/9
 **/
public class SessionCloseExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionCloseExtractor.class);
    private static final int MAX_TURNS = 7;
    private static final double TEMPERATURE = 0.3;
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    private static final String CLOSE_AGENT_PROMPT = "## Role%n"
            + "You are the **session-close agent**. When a session ends, your job is:%n"
            + "1. Create a daily-log for each completed task from this session.%n"
            + "2. Create a lock file listing those daily-logs so MemoryTriggerService can%n"
            + "   pick them up on the next restart for deep knowledge extraction.%n%n"
            + "All directories already exist.%n"
            + MemoryExtractionSpecs.EXTRACTION_SPEC + "%n%n"
            + "## Today%n"
            + "Date: %1$s%n%n"
            + "## Existing files for today%n"
            + "%2$s%n%n"
            + "## Workflow%n%n"
            + "### Step 1 — Create daily-logs%n"
            + "Scan the unprocessed messages (after the cursor) for completed tasks.%n"
            + "For each task, create `.core-ai/daily-logs/%1$s/{taskName}.md`.%n"
            + "Only include these body sections: `## Context` and `## Actions`.%n"
            + "If a daily-log for this task already exists (check the existing files above),%n"
            + "append to it instead of overwriting.%n%n"
            + "### Step 2 — Create the lock file%n"
            + "Once all daily-logs from Step 1 are created, create `.core-ai/daily-logs/%1$s.lock`%n"
            + "listing each daily-log path (one per line) that needs deep extraction on restart:%n"
            + "```%n"
            + "daily-logs/%1$s/{taskName1}.md%n"
            + "daily-logs/%1$s/{taskName2}.md%n"
            + "```%n"
            + "This lock file is a work queue — MemoryTriggerService reads it on next startup,%n"
            + "processes each listed daily-log into wiki pages and episodes, then deletes it.%n%n"
            + "## Available tools%n"
            + "read_file, write_file, edit_file — use whatever helps you complete the workflow.%n"
            + "Do NOT use cursor tools or add_knowledge_log. Episodes update is handled by lock processing.%n%n"
            + "## Workspace%n"
            + "%3$s%n"
            + "%4$s%n"
            + "Current datetime: %5$s%n"
            + "Max turns: %d — after creating the lock file, stop (the session is closing).%n";

    public static void onSessionClose(Agent mainAgent, Path workspace, boolean memoryEnabled,
                                       boolean dailyLogsEnabled,
                                       AtomicReference<String> switchSessionId) {
        if (!memoryEnabled || !dailyLogsEnabled) {
            return;
        }

        var triggerService = MemoryTriggerService.getInstance();
        // set 0 to test
        if (triggerService.getTurnCount() < 2) {
            return;
        }

        if (triggerService.isLockProcessingPending()) {
            triggerService.awaitLockProcessing();
        } else if (switchSessionId != null && switchSessionId.get() != null) {
            triggerService.runIncrementalExtractionAndWait();
        } else {
            doCloseExtraction(mainAgent, workspace);
        }
    }

    private static void doCloseExtraction(Agent mainAgent, Path workspace) {
        var triggerService = MemoryTriggerService.getInstance();
        try {
            int totalMessages = mainAgent.getMessages().size();
            var agent = AgentFork.fork(mainAgent, new AgentFork.ForkConfig("session-close", MAX_TURNS, TEMPERATURE, false, null));
            agent.injectUserMessage(buildCloseExtractionPrompt(workspace, triggerService.extractionCursor.get(), totalMessages));
            agent.continueWithInjectedMessage();
        } catch (Exception e) {
            LOGGER.warn("Session close extraction failed", e);
        }
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("VA_FORMAT_STRING_USES_NEWLINE")
    private static String buildCloseExtractionPrompt(Path workspace, int cursor, int totalMessages) {
        String today = LocalDate.now(MemoryTriggerService.getTimezone()).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String cursorInfo = "Messages 0–" + (cursor - 1) + " have been extracted (cursor=" + cursor
                    + ", total=" + totalMessages + ").";
        String existingFiles = listExistingFiles(workspace, today);
        return CLOSE_AGENT_PROMPT.formatted(today, existingFiles,
                workspace.toAbsolutePath(), cursorInfo,
                ZonedDateTime.now(MemoryTriggerService.getTimezone()).format(DATETIME_FMT), MAX_TURNS - 2);
    }

    private static boolean isNonLockFile(Path p) {
        if (!Files.isRegularFile(p)) return false;
        Path fn = p.getFileName();
        return fn == null || !fn.toString().endsWith(".lock");
    }

    private static String fileNameOrToString(Path p) {
        Path fn = p.getFileName();
        return fn != null ? fn.toString() : p.toString();
    }

    private static String listExistingFiles(Path workspace, String today) {
        var sb = new StringBuilder(256);
        Path dailyDir = workspace.resolve(".core-ai/daily-logs").resolve(today);

        sb.append("Daily-logs dir: ").append(dailyDir.toAbsolutePath()).append('\n');
        if (Files.isDirectory(dailyDir)) {
            try (var s = Files.list(dailyDir)) {
                var files = s.filter(SessionCloseExtractor::isNonLockFile)
                        .map(p -> "  - " + fileNameOrToString(p))
                        .sorted()
                        .collect(Collectors.joining("\n"));
                if (files.isEmpty()) {
                    sb.append("  (empty)\n");
                } else {
                    sb.append(files).append('\n');
                }
            } catch (IOException e) {
                sb.append("  (error listing)\n");
            }
        } else {
            sb.append("  (does not exist yet)\n");
        }
        return sb.toString();
    }
}
