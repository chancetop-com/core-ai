package ai.core.cli.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class CliAgentInstructionsPromptTest {

    @TempDir
    Path workspace;

    private void write(String relativePath, String content) throws IOException {
        var file = workspace.resolve(relativePath);
        var parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content);
    }

    private String inject() {
        return new CliAgentInstructionsPrompt(workspace).inject();
    }

    @Test
    void prefersAgentsMdInCoreAiDirOverEverythingElse() throws IOException {
        write(".core-ai/AGENTS.md", "core-ai agents marker");
        write(".core-ai/instructions.md", "core-ai legacy marker");
        write(".core-ai/CLAUDE.md", "core-ai claude marker");
        write("AGENTS.md", "workspace agents marker");
        write("instructions.md", "workspace legacy marker");

        var prompt = inject();

        assertTrue(prompt.contains("core-ai agents marker"));
        assertFalse(prompt.contains("core-ai legacy marker"));
        assertFalse(prompt.contains("core-ai claude marker"));
        assertFalse(prompt.contains("workspace agents marker"));
        assertFalse(prompt.contains("workspace legacy marker"));
    }

    @Test
    void keepsReadingLegacyInstructionsMd() throws IOException {
        write(".core-ai/instructions.md", "core-ai legacy marker");

        assertTrue(inject().contains("core-ai legacy marker"));
    }

    @Test
    void usesWorkspaceAgentsMdWhenCoreAiDirHasNoInstructions() throws IOException {
        write("AGENTS.md", "workspace agents marker");
        write("instructions.md", "workspace legacy marker");
        write("CLAUDE.md", "workspace claude marker");

        var prompt = inject();

        assertTrue(prompt.contains("workspace agents marker"));
        assertFalse(prompt.contains("workspace legacy marker"));
        assertFalse(prompt.contains("workspace claude marker"));
    }

    @Test
    void fallsBackToWorkspaceClaudeMd() throws IOException {
        write("CLAUDE.md", "workspace claude marker");

        assertTrue(inject().contains("workspace claude marker"));
    }

    @Test
    void returnsEmptyWhenNoInstructionFileExists() {
        assertEquals("", inject());
    }
}
