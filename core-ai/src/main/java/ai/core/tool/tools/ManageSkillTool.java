package ai.core.tool.tools;

import ai.core.skill.SkillMetadata;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Tool that allows the agent to create and manage skills.
 * Skills are markdown files in .core-ai/skills/ directory.
 *
 * @author xander
 */
public class ManageSkillTool extends ToolCall {

    public static final String TOOL_NAME = "manage_skill";

    private static final Logger LOGGER = LoggerFactory.getLogger(ManageSkillTool.class);

    private static final String TOOL_DESC = """
            Manage skill files (create/delete/list). To USE a skill, call use_skill instead.

            Actions:
            - "create": Create a new skill directory with SKILL.md
            - "delete": Delete an existing skill directory
            - "list": List all skills with their SKILL.md paths

            Skill file format (SKILL.md with YAML frontmatter):
            ---
            name: skill-name
            description: What this skill does
            ---
            # Skill content in markdown
            """;

    public static Builder builder() {
        return new Builder();
    }

    private Path skillsDir;
    private Path userSkillsDir;

    @Override
    public ToolCallResult execute(String arguments) {
        long startTime = System.currentTimeMillis();
        try {
            var args = JSON.fromJSON(Map.class, arguments);
            String action = (String) args.get("action");
            if (action == null) action = "list";

            return switch (action) {
                case "create" -> createSkill(args, startTime);
                case "delete" -> deleteSkill(args, startTime);
                default -> listSkills(startTime);
            };
        } catch (Exception e) {
            return ToolCallResult.failed("Failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private ToolCallResult createSkill(Map<?, ?> args, long startTime) throws IOException {
        String name = (String) args.get("name");
        String content = (String) args.get("content");
        if (name == null || name.isBlank()) {
            return ToolCallResult.failed("'name' is required for create action");
        }
        if (!SkillMetadata.isValidName(name)) {
            return ToolCallResult.failed("Invalid skill name '" + name + "': must be lowercase alphanumeric with hyphens, max 64 chars");
        }
        if (content == null || content.isBlank()) {
            return ToolCallResult.failed("'content' is required for create action");
        }
        Path skillDir = skillsDir.resolve(name);
        if (!isWithinDirectory(skillDir, skillsDir)) {
            return ToolCallResult.failed("Invalid skill path");
        }
        Files.createDirectories(skillDir);
        Path file = skillDir.resolve("SKILL.md");
        Files.writeString(file, content);
        LOGGER.info("Created skill: {}", file);
        return ToolCallResult.completed("Skill '" + name + "' created at " + file + ". Restart or /skill to verify.")
                .withDuration(System.currentTimeMillis() - startTime);
    }

    private ToolCallResult deleteSkill(Map<?, ?> args, long startTime) throws IOException {
        String name = (String) args.get("name");
        if (name == null || name.isBlank()) {
            return ToolCallResult.failed("'name' is required for delete action");
        }
        if (!SkillMetadata.isValidName(name)) {
            return ToolCallResult.failed("Invalid skill name '" + name + "'");
        }
        Path skillDir = skillsDir.resolve(name);
        if (!isWithinDirectory(skillDir, skillsDir)) {
            return ToolCallResult.failed("Invalid skill path");
        }
        if (!Files.isDirectory(skillDir)) {
            return ToolCallResult.failed("Skill '" + name + "' not found");
        }
        deleteDirectory(skillDir);
        return ToolCallResult.completed("Skill '" + name + "' deleted.")
                .withDuration(System.currentTimeMillis() - startTime);
    }

    private boolean isWithinDirectory(Path target, Path parent) {
        try {
            Path resolvedParent = parent.toAbsolutePath().normalize();
            Path resolvedTarget = target.toAbsolutePath().normalize();
            return resolvedTarget.startsWith(resolvedParent);
        } catch (Exception e) {
            return false;
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        try (var files = Files.walk(dir)) {
            var paths = files.sorted(Comparator.reverseOrder()).toList();
            for (var p : paths) {
                Files.delete(p);
            }
        }
    }

    private ToolCallResult listSkills(long startTime) throws IOException {
        var skills = new ArrayList<String>();
        scanSkillDir(skillsDir, "workspace", skills);
        if (userSkillsDir != null) {
            scanSkillDir(userSkillsDir, "user", skills);
        }
        if (skills.isEmpty()) {
            return ToolCallResult.completed("No skills found.")
                    .withDuration(System.currentTimeMillis() - startTime);
        }
        return ToolCallResult.completed("Skills: " + skills)
                .withDuration(System.currentTimeMillis() - startTime);
    }

    private void scanSkillDir(Path dir, String source, List<String> result) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve("SKILL.md")))
                    .sorted()
                    .forEach(p -> result.add(p.getFileName().toString() + " (" + source + ") → " + p.resolve("SKILL.md")));
        }
    }

    public static class Builder extends ToolCall.Builder<Builder, ManageSkillTool> {
        private Path skillsDir;
        private Path userSkillsDir;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder skillsDir(Path dir) {
            this.skillsDir = dir;
            return this;
        }

        public Builder userSkillsDir(Path dir) {
            this.userSkillsDir = dir;
            return this;
        }

        public ManageSkillTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "action",
                            "Action: create, delete, or list").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "name",
                            "Skill name"),
                    ToolCallParameters.ParamSpec.of(String.class, "content",
                            "Skill content in markdown with YAML frontmatter (for create)")
            ));
            var tool = new ManageSkillTool();
            build(tool);
            tool.skillsDir = this.skillsDir != null
                    ? this.skillsDir
                    : Path.of(".core-ai/skills");
            tool.userSkillsDir = this.userSkillsDir;
            return tool;
        }
    }
}
