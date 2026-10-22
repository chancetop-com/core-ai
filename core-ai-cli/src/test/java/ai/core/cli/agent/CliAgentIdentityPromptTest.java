package ai.core.cli.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class CliAgentIdentityPromptTest {

    @Test
    void desktopClientGetsTheGeneralAssistantIdentity() {
        var prompt = CliAgent.identityPrompt(true, "desktop").inject();

        assertTrue(prompt.contains("general-purpose AI assistant"), prompt);
        assertTrue(prompt.contains("core-ai desktop app"), prompt);
        assertFalse(prompt.contains("coding agent"), prompt);
        assertFalse(prompt.contains("software engineering tasks"), prompt);
    }

    @Test
    void codingFlagOnlyShapesTheCliIdentity() {
        assertTrue(CliAgent.identityPrompt(true, "cli").inject().contains("the best coding agent"));
        assertTrue(CliAgent.identityPrompt(false, "cli").inject().contains("helpful AI coding assistant"));
    }

    @Test
    void anUnsetClientTypeKeepsTheCliIdentity() {
        assertTrue(CliAgent.identityPrompt(false, null).inject().contains("helpful AI coding assistant"));
    }
}
