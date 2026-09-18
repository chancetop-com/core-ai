package ai.core.cli.memory;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LockProcessingPromptTest {

    @Test
    void formatKeepsExtractionSpecLiteralAndFillsEveryPlaceholder() {
        var lockFile = Path.of("session.lock");
        var workspace = Path.of("workspace");

        var prompt = LockProcessingPrompt.format(lockFile, "- 2026-09-18 task", workspace,
                ZonedDateTime.parse("2026-09-18T10:00:00+08:00[Asia/Shanghai]"), 12);

        assertTrue(prompt.contains("85% on dataset X"), prompt);
        assertTrue(prompt.indexOf("85% on dataset X") < prompt.indexOf("## Allowed Tools"), prompt);
        assertTrue(prompt.contains("Lock file path: " + lockFile.toAbsolutePath()), prompt);
        assertTrue(prompt.contains("- 2026-09-18 task"), prompt);
        assertTrue(prompt.contains("Workspace: " + workspace.toAbsolutePath()), prompt);
        assertTrue(prompt.contains("Current datetime: 2026-09-18 10:00 "), prompt);
        assertTrue(prompt.contains("Max turns: 12"), prompt);
    }
}
