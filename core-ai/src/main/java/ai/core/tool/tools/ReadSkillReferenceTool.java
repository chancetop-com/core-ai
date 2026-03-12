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
 * Tool that allows the agent to read specific reference files from a skill directory.
 *
 * @author xander
 */
public class ReadSkillReferenceTool extends ToolCall {

    public static final String TOOL_NAME = "read_skill_reference";

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadSkillReferenceTool.class);

    private static final String BASE_DESC = """
            Read a reference file from a skill directory.
            Use this after calling use_skill to read detailed reference content.
            """;

    private static final String NO_REFS_DESC = BASE_DESC + "\nNo skill references currently available.";

    public static Builder builder() {
        return new Builder();
    }

    static String buildDescription(List<SkillMetadata> skills) {
        if (skills == null || skills.isEmpty()) {
            return NO_REFS_DESC;
        }
        boolean hasAnyRef = false;
        var sb = new StringBuilder(BASE_DESC.length() + skills.size() * 200);
        sb.append(BASE_DESC).append("\nAvailable references:\n");
        for (var skill : skills) {
            var refs = skill.getReferences();
            if (refs == null || refs.isEmpty()) {
                continue;
            }
            hasAnyRef = true;
            sb.append("Skill '").append(skill.getName()).append("':\n");
            for (var ref : refs) {
                sb.append("  - ").append(ref.file());
                if (ref.description() != null && !ref.description().isBlank()) {
                    sb.append(": ").append(ref.description());
                }
                sb.append('\n');
            }
        }
        if (!hasAnyRef) {
            return NO_REFS_DESC;
        }
        return sb.toString();
    }

    private List<SkillMetadata> loadedSkills;
    private int maxFileSize;

    @Override
    public ToolCallResult execute(String arguments) {
        long startTime = System.currentTimeMillis();
        try {
            var args = JSON.fromJSON(Map.class, arguments);
            String skillName = (String) args.get("skill_name");
            String file = (String) args.get("file");
            if (skillName == null || skillName.isBlank()) {
                return ToolCallResult.failed("'skill_name' is required")
                        .withDuration(System.currentTimeMillis() - startTime);
            }
            if (file == null || file.isBlank()) {
                return ToolCallResult.failed("'file' is required")
                        .withDuration(System.currentTimeMillis() - startTime);
            }
            return readReference(skillName.trim(), file.trim(), startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private ToolCallResult readReference(String skillName, String file, long startTime) {
        SkillMetadata skill = findSkill(skillName);
        if (skill == null) {
            return ToolCallResult.failed("Skill '" + skillName + "' not found")
                    .withDuration(System.currentTimeMillis() - startTime);
        }
        if (skill.getSkillDir() == null) {
            return ToolCallResult.failed("Skill '" + skillName + "' has no directory")
                    .withDuration(System.currentTimeMillis() - startTime);
        }
        Path refPath = Path.of(skill.getSkillDir(), file);
        if (!isWithinDirectory(refPath, Path.of(skill.getSkillDir()))) {
            return ToolCallResult.failed("Invalid file path: path traversal not allowed")
                    .withDuration(System.currentTimeMillis() - startTime);
        }
        if (!Files.isRegularFile(refPath)) {
            return ToolCallResult.failed("Reference file '" + file + "' not found in skill '" + skillName + "'")
                    .withDuration(System.currentTimeMillis() - startTime);
        }
        try {
            byte[] bytes = Files.readAllBytes(refPath);
            if (bytes.length > maxFileSize) {
                return ToolCallResult.failed("Reference file exceeds max size: " + bytes.length)
                        .withDuration(System.currentTimeMillis() - startTime);
            }
            return ToolCallResult.completed(new String(bytes, StandardCharsets.UTF_8))
                    .withDuration(System.currentTimeMillis() - startTime);
        } catch (IOException e) {
            LOGGER.warn("Failed to read reference file: {}", refPath, e);
            return ToolCallResult.failed("Failed to read reference: " + e.getMessage())
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

    private boolean isWithinDirectory(Path file, Path directory) {
        try {
            Path normalizedFile = file.normalize().toAbsolutePath();
            Path normalizedDir = directory.normalize().toAbsolutePath();
            return normalizedFile.startsWith(normalizedDir);
        } catch (Exception e) {
            return false;
        }
    }

    public static class Builder extends ToolCall.Builder<Builder, ReadSkillReferenceTool> {
        private List<SkillSource> sources;
        private int maxFileSize = 10 * 1024 * 1024;

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

        public ReadSkillReferenceTool build() {
            var loader = new SkillLoader(maxFileSize);
            List<SkillMetadata> allSkills = sources != null ? loader.loadAll(sources) : List.of();

            this.name(TOOL_NAME);
            this.description(buildDescription(allSkills));
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "skill_name", "Name of the skill").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "file", "Reference file path relative to skill directory").required()
            ));

            var tool = new ReadSkillReferenceTool();
            build(tool);
            tool.loadedSkills = allSkills;
            tool.maxFileSize = this.maxFileSize;
            return tool;
        }
    }
}
