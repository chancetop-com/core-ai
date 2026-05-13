package ai.core.cli.memory;

import ai.core.agent.Agent;
import ai.core.cli.utils.AgentFork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final String CLOSE_AGENT_PROMPT = """
            ## Role
            You are the **session-close agent**. When a session ends, your job is:
            1. Create a daily-log for each completed task from this session.
            2. Create a lock file listing those daily-logs so MemoryTriggerService can
               pick them up on the next restart for deep knowledge extraction.
            
            All directories already exist. File formats are defined in your system prompt
            (see the ## Memory section for daily-logs format, kebab-case file naming, etc.).
            
            ## Today
            Date: %s
            
            ## Existing files for today
            %s
            
            ## Workflow
            
            ### Step 1 — Create daily-logs
            Scan the unprocessed messages (after the cursor) for completed tasks.
            For each task, create `.core-ai/daily-logs/%s/{taskName}.md`.
            Only include these sections in the YAML frontmatter: task, date, result.
            Only include these body sections: `## Context` and `## Actions`.
            If a daily-log for this task already exists (check the existing files above),
            append to it instead of overwriting.
            
            ### Step 2 — Create the lock file
            Once all daily-logs from Step 1 are created, create `.core-ai/daily-logs/%s.lock`
            listing each daily-log path (one per line) that needs deep extraction on restart:
            ```
            daily-logs/%s/{taskName1}.md
            daily-logs/%s/{taskName2}.md
            ```
            This lock file is a work queue — MemoryTriggerService reads it on next startup,
            processes each listed daily-log into wiki pages and episodes, then deletes it.
            
            ## Available tools
            read_file, write_file, edit_file — use whatever helps you complete the workflow.
            Do NOT use cursor tools or add_knowledge_log. Episodes update is handled by lock processing.
            
            ## Workspace
            %s
            %s
            Current datetime: %s
            Max turns: %d — after creating the lock file, stop (the session is closing).
            """;

    public static void onSessionClose(Agent mainAgent, Path workspace, boolean memoryEnabled,
                                      AtomicReference<String> switchSessionId) {
        if (!memoryEnabled) {
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

    private static String buildCloseExtractionPrompt(Path workspace, int cursor, int totalMessages) {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String cursorInfo = "Messages 0–" + (cursor - 1) + " have been extracted (cursor=" + cursor
                    + ", total=" + totalMessages + ").";
        String existingFiles = listExistingFiles(workspace, today);
        return CLOSE_AGENT_PROMPT.formatted(today, existingFiles,
                today, today, today, today,
                workspace.toAbsolutePath(), cursorInfo,
                LocalDateTime.now().format(DATETIME_FMT), MAX_TURNS - 2);
    }

    private static String listExistingFiles(Path workspace, String today) {
        var sb = new StringBuilder();
        Path dailyDir = workspace.resolve(".core-ai/daily-logs").resolve(today);

        sb.append("Daily-logs dir: ").append(dailyDir.toAbsolutePath()).append("\n");
        if (Files.isDirectory(dailyDir)) {
            try (var s = Files.list(dailyDir)) {
                var files = s.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().endsWith(".lock"))
                        .map(p -> "  - " + p.getFileName())
                        .sorted()
                        .collect(Collectors.joining("\n"));
                if (files.isEmpty()) {
                    sb.append("  (empty)\n");
                } else {
                    sb.append(files).append("\n");
                }
            } catch (Exception e) {
                sb.append("  (error listing)\n");
            }
        } else {
            sb.append("  (does not exist yet)\n");
        }
        return sb.toString();
    }
}
