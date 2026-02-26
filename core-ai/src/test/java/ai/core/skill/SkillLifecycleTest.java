package ai.core.skill;

import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author xander
 */
class SkillLifecycleTest {

    @Test
    void beforeModelInjectsSkillsIntoSystemPrompt(@TempDir Path tempDir) throws IOException {
        createSkillFixture(tempDir, "web-research", "Provides web research methodology");
        createSkillFixture(tempDir, "code-review", "Provides code review checklist");

        var config = SkillConfig.of(tempDir.toString());
        var lifecycle = new SkillLifecycle(config);
        lifecycle.afterAgentBuild(null);

        var systemMsg = Message.of(RoleType.SYSTEM, "You are a helpful assistant");
        var request = new CompletionRequest();
        request.messages = new ArrayList<>(List.of(systemMsg, Message.of(RoleType.USER, "hello")));

        lifecycle.beforeModel(request, ExecutionContext.builder().build());

        String systemText = request.messages.getFirst().getTextContent();
        assertTrue(systemText.contains("## Skills"));
        assertTrue(systemText.contains("web-research"));
        assertTrue(systemText.contains("code-review"));
        assertTrue(systemText.contains("SKILL.md"));
    }

    @Test
    void beforeModelDoesNothingWhenNoSkills() {
        var config = SkillConfig.of("/non/existent/path");
        var lifecycle = new SkillLifecycle(config);
        lifecycle.afterAgentBuild(null);

        var systemMsg = Message.of(RoleType.SYSTEM, "You are a helpful assistant");
        var request = new CompletionRequest();
        request.messages = new ArrayList<>(List.of(systemMsg));

        lifecycle.beforeModel(request, ExecutionContext.builder().build());

        assertEquals("You are a helpful assistant", request.messages.getFirst().getTextContent());
    }

    @Test
    void beforeModelDoesNothingWhenDisabled() {
        var config = SkillConfig.disabled();
        var lifecycle = new SkillLifecycle(config);
        lifecycle.afterAgentBuild(null);

        assertNull(lifecycle.getLoadedSkills());
    }

    @Test
    void beforeModelHandlesNullRequest() {
        var config = SkillConfig.of("/non/existent");
        var lifecycle = new SkillLifecycle(config);
        lifecycle.afterAgentBuild(null);
        lifecycle.beforeModel(null, null);
    }

    @Test
    void beforeModelHandlesEmptyMessages(@TempDir Path tempDir) throws IOException {
        createSkillFixture(tempDir, "test-skill", "A test skill");
        var config = SkillConfig.of(tempDir.toString());
        var lifecycle = new SkillLifecycle(config);
        lifecycle.afterAgentBuild(null);

        var request = new CompletionRequest();
        request.messages = new ArrayList<>();
        lifecycle.beforeModel(request, ExecutionContext.builder().build());

        assertTrue(request.messages.isEmpty());
    }

    @Test
    void skillsFormattedWithPathForOnDemandLoading(@TempDir Path tempDir) throws IOException {
        createSkillFixture(tempDir, "my-skill", "My test skill description");
        var config = SkillConfig.of(tempDir.toString());
        var lifecycle = new SkillLifecycle(config);
        lifecycle.afterAgentBuild(null);

        assertFalse(lifecycle.getLoadedSkills().isEmpty());
        var skill = lifecycle.getLoadedSkills().getFirst();
        assertTrue(skill.getPath().endsWith("SKILL.md"));
    }

    private void createSkillFixture(Path baseDir, String name, String description) throws IOException {
        var skillDir = baseDir.resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---
                # %s Skill
                """.formatted(name, description, name));
    }
}
