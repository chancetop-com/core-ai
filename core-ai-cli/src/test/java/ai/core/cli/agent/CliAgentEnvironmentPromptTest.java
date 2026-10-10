package ai.core.cli.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class CliAgentEnvironmentPromptTest {

    @TempDir
    Path workspace;

    @Test
    void desktopClientReportsTheDesktopInterface() {
        var prompt = new CliAgentEnvironmentPrompt(workspace, "desktop").inject();

        assertTrue(prompt.contains("Interface: core-ai desktop app"), prompt);
        assertTrue(prompt.contains("not a terminal"), prompt);
        assertTrue(prompt.contains("embedded browser pane"), prompt);
    }

    @Test
    void cliAndUnsetClientReportTheCliInterface() {
        assertEquals("core-ai CLI", CliAgentEnvironmentPrompt.interfaceLabel("cli"));
        assertEquals("core-ai CLI", CliAgentEnvironmentPrompt.interfaceLabel(null));
        assertTrue(new CliAgentEnvironmentPrompt(workspace, "cli").inject().contains("Interface: core-ai CLI"));
    }

    @Test
    void keepsWorkspaceFactsBesideTheInterface() {
        var prompt = new CliAgentEnvironmentPrompt(workspace, "cli").inject();

        assertTrue(prompt.contains("Working directory: " + workspace.toAbsolutePath()), prompt);
        assertTrue(prompt.contains("Platform:"));
        assertTrue(prompt.contains("Today's date:"));
    }
}
