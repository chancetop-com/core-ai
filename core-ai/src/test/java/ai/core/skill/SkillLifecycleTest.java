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
    void beforeModelNoLongerInjectsSystemPrompt(@TempDir Path tempDir) throws IOException {
        createSkillFixture(tempDir, "web-research", "Provides web research methodology");

        var config = SkillConfig.of(tempDir.toString());
        var lifecycle = new SkillLifecycle(config);
        lifecycle.afterAgentBuild(null);

        var systemMsg = Message.of(RoleType.SYSTEM, "You are a helpful assistant");
        var request = new CompletionRequest();
        request.messages = new ArrayList<>(List.of(systemMsg, Message.of(RoleType.USER, "hello")));

        lifecycle.beforeModel(request, ExecutionContext.builder().build());

        String systemText = request.messages.getFirst().getTextContent();
        assertEquals("You are a helpful assistant", systemText);
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
    void afterAgentBuildLoadsSkills(@TempDir Path tempDir) throws IOException {
        createSkillFixture(tempDir, "web-research", "Provides web research methodology");
        createSkillFixture(tempDir, "code-review", "Provides code review checklist");

        var config = SkillConfig.of(tempDir.toString());
        var lifecycle = new SkillLifecycle(config);
        lifecycle.afterAgentBuild(null);

        var skills = lifecycle.getLoadedSkills();
        assertEquals(2, skills.size());
        assertTrue(skills.stream().anyMatch(s -> "web-research".equals(s.getName())));
        assertTrue(skills.stream().anyMatch(s -> "code-review".equals(s.getName())));
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

    @Test
    void skillResourcesLoadedCorrectly(@TempDir Path tempDir) throws IOException {
        var skillDir = tempDir.resolve("my-skill");
        var scriptsDir = skillDir.resolve("scripts");
        Files.createDirectories(scriptsDir);
        Files.writeString(scriptsDir.resolve("collect.sh"), "#!/bin/sh");
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: my-skill
                description: Skill with scripts
                ---
                """);

        var config = SkillConfig.of(tempDir.toString());
        var lifecycle = new SkillLifecycle(config);
        lifecycle.afterAgentBuild(null);

        var skill = lifecycle.getLoadedSkills().getFirst();
        assertTrue(skill.getReferences().stream().anyMatch(r -> "scripts/collect.sh".equals(r.file())));
    }

    @Test
    void recommendMatchesByTrigger(@TempDir Path tempDir) throws IOException {
        createSkillWithTriggers(tempDir, "code-review", "Code review skill",
                List.of("review code", "check code quality"));

        var config = SkillConfig.builder()
                .source("test", tempDir.toString(), 0)
                .recommendEnabled(true)
                .build();
        var lifecycle = new SkillLifecycle(config);
        lifecycle.afterAgentBuild(null);

        var request = new CompletionRequest();
        request.messages = new ArrayList<>(List.of(
                Message.of(RoleType.SYSTEM, "You are helpful"),
                Message.of(RoleType.USER, "please review code for this module")));

        lifecycle.beforeModel(request, ExecutionContext.builder().build());

        assertEquals(3, request.messages.size());
        assertTrue(request.messages.get(2).getTextContent().contains("code-review"));
    }

    @Test
    void recommendDisabledByDefault(@TempDir Path tempDir) throws IOException {
        createSkillWithTriggers(tempDir, "code-review", "Code review skill",
                List.of("review code"));

        var config = SkillConfig.of(tempDir.toString());
        var lifecycle = new SkillLifecycle(config);
        lifecycle.afterAgentBuild(null);

        var request = new CompletionRequest();
        request.messages = new ArrayList<>(List.of(
                Message.of(RoleType.SYSTEM, "You are helpful"),
                Message.of(RoleType.USER, "review code please")));

        lifecycle.beforeModel(request, ExecutionContext.builder().build());

        assertEquals(2, request.messages.size());
    }

    @Test
    void recommendNoMatchDoesNotInject(@TempDir Path tempDir) throws IOException {
        createSkillWithTriggers(tempDir, "code-review", "Code review skill",
                List.of("review code"));

        var config = SkillConfig.builder()
                .source("test", tempDir.toString(), 0)
                .recommendEnabled(true)
                .build();
        var lifecycle = new SkillLifecycle(config);
        lifecycle.afterAgentBuild(null);

        var request = new CompletionRequest();
        request.messages = new ArrayList<>(List.of(
                Message.of(RoleType.SYSTEM, "You are helpful"),
                Message.of(RoleType.USER, "what is the weather today")));

        lifecycle.beforeModel(request, ExecutionContext.builder().build());

        assertEquals(2, request.messages.size());
    }

    private void createSkillWithTriggers(Path baseDir, String name, String description, List<String> triggers) throws IOException {
        var skillDir = baseDir.resolve(name);
        Files.createDirectories(skillDir);
        var sb = new StringBuilder(256);
        sb.append("---\nname: ").append(name).append("\ndescription: ").append(description).append("\ntriggers:\n");
        for (String trigger : triggers) {
            sb.append("  - \"").append(trigger).append("\"\n");
        }
        sb.append("---\n# ").append(name).append(" Skill\n");
        Files.writeString(skillDir.resolve("SKILL.md"), sb.toString());
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
