package ai.core.tool.tools;

import ai.core.skill.SkillLoader;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillSource;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Tool that allows the agent to load and use skills by name.
 * LLM calls use_skill(name) to get skill full content and resources.
 *
 * @author xander
 */
public class SkillTool extends ToolCall {

    public static final String TOOL_NAME = "use_skill";

    private static final Logger LOGGER = LoggerFactory.getLogger(SkillTool.class);

    private static final String BASE_DESC = """
            Use a skill to accomplish specialized tasks.
            When a skill matches the user's request, you MUST call this tool IMMEDIATELY.
            Do NOT attempt the task without the skill when one is available.
            """;

    private static final String NO_SKILLS_DESC = BASE_DESC + "\nNo skills currently available.";

    public static Builder builder() {
        return new Builder();
    }

    static String buildDescription(List<SkillMetadata> skills, String workspaceDir) {
        if (skills == null || skills.isEmpty()) {
            return NO_SKILLS_DESC;
        }
        var sb = new StringBuilder(BASE_DESC.length() + skills.size() * 200);
        sb.append(BASE_DESC).append("\n<available_skills>\n");
        for (var skill : skills) {
            sb.append("  <skill>\n");
            sb.append("    <name>").append(skill.getName()).append("</name>\n");
            sb.append("    <description>").append(skill.getDescription()).append("</description>\n");
            if (skill.getSkillDir() != null) {
                sb.append("    <location>").append(toDisplayPath(skill.getSkillDir(), workspaceDir)).append("</location>\n");
            }
            sb.append("  </skill>\n");
        }
        sb.append("</available_skills>\n\nProvide the skill name to get full instructions and resources.");
        return sb.toString();
    }

    private static String toDisplayPath(String path, String workspaceDir) {
        String home = System.getProperty("user.home");
        if (workspaceDir != null && path.startsWith(workspaceDir)) {
            return "." + path.substring(workspaceDir.length());
        }
        if (home != null && path.startsWith(home)) {
            return "~" + path.substring(home.length());
        }
        return path;
    }

    private List<SkillMetadata> loadedSkills;
    private int maxFileSize;

    @Override
    public ToolCallResult execute(String arguments) {
        long startTime = System.currentTimeMillis();
        try {
            var args = JSON.fromJSON(Map.class, arguments);
            String name = (String) args.get("name");
            if (name == null || name.isBlank()) {
                return ToolCallResult.failed("'name' is required. Available skills: " + skillNameList())
                        .withDuration(System.currentTimeMillis() - startTime);
            }
            return loadSkill(name.trim(), startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private ToolCallResult loadSkill(String name, long startTime) {
        SkillMetadata skill = findSkill(name);
        if (skill == null) {
            return ToolCallResult.failed("Skill '" + name + "' not found. Available skills: " + skillNameList())
                    .withDuration(System.currentTimeMillis() - startTime);
        }
        try {
            String content = readSkillContent(skill);
            return ToolCallResult.completed(content)
                    .withDuration(System.currentTimeMillis() - startTime);
        } catch (IOException e) {
            LOGGER.warn("Failed to read skill content: {}", skill.getPath(), e);
            return ToolCallResult.failed("Failed to read skill '" + name + "': " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private SkillMetadata findSkill(String name) {
        if (loadedSkills == null) return null;
        for (var skill : loadedSkills) {
            if (skill.getName().equals(name)) return skill;
        }
        return null;
    }

    private String readSkillContent(SkillMetadata skill) throws IOException {
        byte[] bytes = Files.readAllBytes(Path.of(skill.getPath()));
        if (bytes.length > maxFileSize) {
            throw new IOException("Skill file exceeds max size: " + bytes.length + " > " + maxFileSize);
        }
        String skillMd = new String(bytes, StandardCharsets.UTF_8);
        var sb = new StringBuilder(skillMd.length() + 256);
        sb.append("<skill name=\"").append(skill.getName()).append('"');
        if (skill.getSkillDir() != null) {
            sb.append(" base_dir=\"").append(skill.getSkillDir()).append('"');
        }
        sb.append(">\n").append(skillMd);
        List<String> resources = skill.getResources();
        if (!resources.isEmpty()) {
            sb.append("\n\nResources:\n");
            for (String resource : resources) {
                sb.append("- ").append(resource).append('\n');
            }
        }
        sb.append("</skill>");
        return sb.toString();
    }

    private String skillNameList() {
        if (loadedSkills == null || loadedSkills.isEmpty()) return "(none)";
        var sb = new StringBuilder();
        for (int i = 0; i < loadedSkills.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(loadedSkills.get(i).getName());
        }
        return sb.toString();
    }

    public static class Builder extends ToolCall.Builder<Builder, SkillTool> {
        private List<SkillSource> sources;
        private int maxFileSize = 10 * 1024 * 1024;
        private String workspaceDir;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder sources(List<SkillSource> sources) {
            this.sources = sources;
            return this;
        }

        public Builder maxFileSize(int maxFileSize) {
            this.maxFileSize = maxFileSize;
            return this;
        }

        public Builder workspaceDir(String workspaceDir) {
            this.workspaceDir = workspaceDir;
            return this;
        }

        public SkillTool build() {
            var loader = new SkillLoader(maxFileSize);
            List<SkillMetadata> skills = sources != null ? loader.loadAll(sources) : List.of();

            this.name(TOOL_NAME);
            this.description(buildDescription(skills, workspaceDir));
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "name", "Skill name to use").required()
            ));

            var tool = new SkillTool();
            build(tool);
            tool.loadedSkills = skills;
            tool.maxFileSize = this.maxFileSize;
            return tool;
        }
    }
}
