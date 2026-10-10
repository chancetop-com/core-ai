package ai.core.cli.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class CliAgentManagedBrowserPromptTest {

    @Test
    void pointsTheAgentAtTheAlreadyAttachedBrowser() {
        var prompt = new CliAgentManagedBrowserPrompt().inject();

        assertTrue(prompt.contains("already attached to the app's built-in browser pane"), prompt);
        assertTrue(prompt.contains("BU_CDP_URL"), prompt);
        assertTrue(prompt.contains("run browser-use directly"), prompt);
    }

    @Test
    void forbidsTheManualBrowserLifecycle() {
        var prompt = new CliAgentManagedBrowserPrompt().inject();

        assertTrue(prompt.contains("never start or attach to another browser"), prompt);
        assertTrue(prompt.contains("never touch ~/.core-ai/browser-profiles"), prompt);
        assertFalse(prompt.contains("Start-Process msedge"), prompt);
    }

    @Test
    void routesLoginsToTheAppPanel() {
        var prompt = new CliAgentManagedBrowserPrompt().inject();

        assertTrue(prompt.contains("sign in inside the app's browser panel"), prompt);
    }
}
