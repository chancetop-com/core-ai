package ai.core.tool.tools;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
            Create, update, or delete skill files in the project's .core-ai/skills/ directory.
            Skills are markdown files with YAML frontmatter that extend agent capabilities.

            Actions:
            - "create": Create a new skill file with the given name and content
            - "delete": Delete an existing skill file
            - "list": List all skill files in the skills directory

            Skill file format:
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
        if (content == null || content.isBlank()) {
            return ToolCallResult.failed("'content' is required for create action");
        }
        Files.createDirectories(skillsDir);
        Path file = skillsDir.resolve(name + ".md");
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
        Path file = skillsDir.resolve(name + ".md");
        if (!Files.exists(file)) {
            return ToolCallResult.failed("Skill '" + name + "' not found");
        }
        Files.delete(file);
        return ToolCallResult.completed("Skill '" + name + "' deleted.")
                .withDuration(System.currentTimeMillis() - startTime);
    }

    private ToolCallResult listSkills(long startTime) throws IOException {
        if (!Files.isDirectory(skillsDir)) {
            return ToolCallResult.completed("No skills directory found at " + skillsDir);
        }
        try (var stream = Files.list(skillsDir)) {
            var skills = stream
                    .filter(p -> p.toString().endsWith(".md"))
                    .map(p -> p.getFileName().toString().replace(".md", ""))
                    .sorted()
                    .toList();
            if (skills.isEmpty()) {
                return ToolCallResult.completed("No skills found in " + skillsDir);
            }
            return ToolCallResult.completed("Skills: " + skills)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    public static class Builder extends ToolCall.Builder<Builder, ManageSkillTool> {
        private Path skillsDir;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder skillsDir(Path dir) {
            this.skillsDir = dir;
            return this;
        }

        public ManageSkillTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "action",
                            "Action: create, delete, or list").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "name",
                            "Skill name (without .md extension)"),
                    ToolCallParameters.ParamSpec.of(String.class, "content",
                            "Skill content in markdown with YAML frontmatter (for create)")
            ));
            var tool = new ManageSkillTool();
            build(tool);
            tool.skillsDir = this.skillsDir != null
                    ? this.skillsDir
                    : Path.of(".core-ai/skills");
            return tool;
        }
    }
}
