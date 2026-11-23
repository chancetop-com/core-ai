package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.skill.FilesystemSkillProvider;
import ai.core.skill.SkillCapability;
import ai.core.skill.SkillLoadException;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillRegistry;
import ai.core.skill.SkillSdkCapabilityManifest;
import ai.core.skill.SkillSource;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.tool.mcp.McpToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    public static String buildDescription(List<SkillMetadata> skills, String workspaceDir) {
        if (skills == null || skills.isEmpty()) {
            return NO_SKILLS_DESC;
        }
        var sb = new StringBuilder(BASE_DESC.length() + skills.size() * 200);
        sb.append(BASE_DESC).append("\n<available_skills>\n");
        for (var skill : skills) {
            sb.append("  <skill>\n    <name>").append(skill.getQualifiedName())
              .append("</name>\n    <description>").append(skill.getDescription()).append("</description>\n");
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

    private SkillRegistry registry;
    private SkillAccessGuard accessGuard;

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        return doExecute(arguments, context);
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return doExecute(arguments, null);
    }

    private ToolCallResult doExecute(String arguments, ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        try {
            var args = parseArguments(arguments);
            String name = getStringValue(args, "name");
            if (name == null || name.isBlank()) {
                return ToolCallResult.failed("'name' is required. Available skills: " + skillNameList())
                        .withDuration(System.currentTimeMillis() - startTime);
            }
            return loadSkill(name.trim(), startTime, context);
        } catch (Exception e) {
            return ToolCallResult.failed("Failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private ToolCallResult loadSkill(String name, long startTime, ExecutionContext context) {
        SkillMetadata skill = registry != null ? registry.find(name) : null;
        if (skill == null) {
            return ToolCallResult.failed("Skill '" + name + "' not found. Available skills: " + skillNameList())
                    .withDuration(System.currentTimeMillis() - startTime);
        }
        if (accessGuard != null && !accessGuard.allow(skill)) {
            return ToolCallResult.failed("Skill '" + name + "' is unavailable: the plugin that provides it is "
                            + "disabled or its permission was revoked. Do not retry it; continue without this skill.")
                    .withDuration(System.currentTimeMillis() - startTime);
        }
        try {
            String content = readSkillContent(skill);
            applyCapabilities(skill, context);
            return ToolCallResult.completed(content)
                    .withDuration(System.currentTimeMillis() - startTime);
        } catch (SkillLoadException e) {
            LOGGER.warn("Failed to read skill content: {}", skill.getQualifiedName(), e);
            return ToolCallResult.failed("Failed to read skill '" + name + "': " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private void applyCapabilities(SkillMetadata skill, ExecutionContext context) {
        if (context == null) return;
        var manifest = SkillSdkCapabilityManifest.parse(skill.getAllowedTools(), skill.getMetadata());
        if (!manifest.enabled()) return;
        var registry = context.getToolRegistry();
        if (registry == null) return;
        var tools = registry.materialize(context).getDispatchMap().values();
        var capabilities = new ArrayList<SkillCapability>(context.getSkillCapabilities());
        for (var entry : manifest.entries()) {
            var tool = match(entry, tools);
            if (tool == null) continue;
            var capability = capability(skill, entry, tool);
            if (capabilities.stream().noneMatch(existing -> existing.refId().equals(capability.refId())
                    && existing.tool().getName().equals(capability.tool().getName()))) {
                capabilities.add(capability);
            }
        }
        context.setSkillCapabilities(capabilities);
        context.activateSkillCapabilityScope();
    }

    private ToolCall match(SkillSdkCapabilityManifest.Entry entry, java.util.Collection<ToolCall> tools) {
        var parts = entry.parts();
        if (SkillSdkCapabilityManifest.KIND_MCP.equals(entry.kind()) && parts.size() == 2) {
            for (var tool : tools) {
                if (!"mcp".equals(tool.getSourceType())) continue;
                if (!(tool instanceof McpToolCall mcp)) continue;
                if (!parts.get(0).equals(mcp.getServerLabel())) continue;
                if (tool.getName().equals(parts.get(1)) || tool.getName().endsWith("_" + parts.get(1))) return tool;
            }
            return null;
        }
        if (SkillSdkCapabilityManifest.KIND_TOOL.equals(entry.kind()) && parts.size() == 1) {
            var wanted = normalize(parts.getFirst());
            for (var tool : tools) {
                if (normalize(tool.getName()).equals(wanted)) return tool;
            }
        }
        return null;
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private SkillCapability capability(SkillMetadata skill, SkillSdkCapabilityManifest.Entry entry, ToolCall tool) {
        var parts = entry.parts();
        if (SkillSdkCapabilityManifest.KIND_MCP.equals(entry.kind())) {
            return new SkillCapability(skill.getQualifiedName(), skill.getQualifiedName(), "mcp",
                    "mcp:" + parts.get(0) + ":" + parts.get(1),
                    parts.get(0) + "/" + parts.get(1), parts.get(0), tool);
        }
        return new SkillCapability(skill.getQualifiedName(), skill.getQualifiedName(), "builtin",
                tool.getName(), tool.getName(), tool.getNamespace(), tool);
    }

    private String readSkillContent(SkillMetadata skill) {
        String skillMd = registry.readContent(skill);
        var sb = new StringBuilder(skillMd.length() + 256);
        sb.append("<skill name=\"").append(skill.getQualifiedName()).append('"');
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
        sb.append(SkillSdkCapabilityManifest.parse(skill.getAllowedTools(), skill.getMetadata()).summary())
                .append("</skill>");
        return sb.toString();
    }

    private String skillNameList() {
        if (registry == null) return "(none)";
        var skills = registry.listAll();
        if (skills.isEmpty()) return "(none)";
        var sb = new StringBuilder();
        for (int i = 0; i < skills.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(skills.get(i).getQualifiedName());
        }
        return sb.toString();
    }

    /** Checked immediately before a skill's content is read, so revocation applies mid-turn. */
    @FunctionalInterface
    public interface SkillAccessGuard {
        boolean allow(SkillMetadata skill);
    }

    public static class Builder extends ToolCall.Builder<Builder, SkillTool> {
        private SkillRegistry registry;
        private List<SkillSource> sources;
        private int maxFileSize = 10 * 1024 * 1024;
        private String workspaceDir;
        private SkillAccessGuard accessGuard;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder registry(SkillRegistry registry) {
            this.registry = registry;
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

        public Builder accessGuard(SkillAccessGuard accessGuard) {
            this.accessGuard = accessGuard;
            return this;
        }

        public SkillTool build() {
            if (registry == null) {
                registry = new SkillRegistry();
                if (sources != null) {
                    for (var source : sources) {
                        registry.addProvider(new FilesystemSkillProvider(
                            source.name(), source.path(), source.priority(), maxFileSize));
                    }
                }
            }

            List<SkillMetadata> skills = registry.listAll();

            this.name(TOOL_NAME);
            this.description(buildDescription(skills, workspaceDir));
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "name", "Skill name to use").required()
            ));

            var tool = new SkillTool();
            build(tool);
            tool.registry = this.registry;
            tool.accessGuard = this.accessGuard;
            return tool;
        }
    }
}
