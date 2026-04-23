package ai.core.server.skill;

import ai.core.agent.ExecutionContext;
import ai.core.sandbox.Sandbox;
import ai.core.server.domain.SkillDefinition;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillRegistry;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.tool.tools.SkillTool;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Server-side use_skill tool. When invoked, materializes the requested skill
 * into the active sandbox at /skill/{name}/, then returns SKILL.md with
 * base_dir pointing at that sandbox path. Replaces the default SkillTool
 * for sessions with a sandbox.
 *
 * @author xander
 */
public class ServerSkillTool extends ToolCall {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerSkillTool.class);
    private static final String SANDBOX_SKILL_BASE = "/skill";

    public static Builder builder() {
        return new Builder();
    }

    private SkillRegistry registry;
    private SkillService skillService;
    private SkillArchiveBuilder archiveBuilder;

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed("ServerSkillTool requires ExecutionContext; direct execute is not supported");
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        try {
            var args = JSON.fromJSON(Map.class, arguments);
            var name = (String) args.get("name");
            if (name == null || name.isBlank()) {
                return ToolCallResult.failed("'name' is required. Available: " + skillNameList())
                        .withDuration(System.currentTimeMillis() - startTime);
            }

            var meta = registry.find(name.trim());
            if (meta == null) {
                return ToolCallResult.failed("Skill '" + name + "' not found. Available: " + skillNameList())
                        .withDuration(System.currentTimeMillis() - startTime);
            }

            var definition = skillService.findByQualifiedName(meta.getQualifiedName());
            var baseDir = materializeIfPossible(context.getSandbox(), definition);

            return ToolCallResult.completed(renderSkill(meta, definition, baseDir))
                    .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            LOGGER.warn("use_skill failed", e);
            return ToolCallResult.failed("Failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private String materializeIfPossible(Sandbox sandbox, SkillDefinition def) {
        if (sandbox == null) {
            LOGGER.warn("no sandbox attached; skill content returned without materialization: {}", def.qualifiedName);
            return null;
        }
        try {
            var archive = archiveBuilder.build(def);
            sandbox.materializeSkill(def.name, def.version, archive);
            return SANDBOX_SKILL_BASE + "/" + def.name;
        } catch (UnsupportedOperationException e) {
            LOGGER.warn("sandbox does not support materialization: {}", sandbox.getId());
            return null;
        }
    }

    private String renderSkill(SkillMetadata meta, SkillDefinition def, String baseDir) {
        var content = def.content != null ? def.content : "";
        var sb = new StringBuilder(content.length() + 256);
        sb.append("<skill name=\"").append(meta.getQualifiedName()).append('"');
        if (baseDir != null) {
            sb.append(" base_dir=\"").append(baseDir).append('"');
        }
        sb.append(">\n").append(content);
        if (def.resources != null && !def.resources.isEmpty()) {
            sb.append("\n\nResources:\n");
            for (var r : def.resources) {
                sb.append("- ").append(r.path).append('\n');
            }
        }
        sb.append("</skill>");
        return sb.toString();
    }

    private String skillNameList() {
        var skills = registry != null ? registry.listAll() : List.<SkillMetadata>of();
        if (skills.isEmpty()) return "(none)";
        var sb = new StringBuilder();
        for (int i = 0; i < skills.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(skills.get(i).getQualifiedName());
        }
        return sb.toString();
    }

    public static class Builder extends ToolCall.Builder<Builder, ServerSkillTool> {
        private SkillRegistry registry;
        private SkillService skillService;
        private SkillArchiveBuilder archiveBuilder;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder registry(SkillRegistry registry) {
            this.registry = registry;
            return this;
        }

        public Builder skillService(SkillService skillService) {
            this.skillService = skillService;
            return this;
        }

        public Builder archiveBuilder(SkillArchiveBuilder archiveBuilder) {
            this.archiveBuilder = archiveBuilder;
            return this;
        }

        public ServerSkillTool build() {
            if (registry == null || skillService == null || archiveBuilder == null) {
                throw new RuntimeException("registry, skillService and archiveBuilder are required");
            }
            this.name(SkillTool.TOOL_NAME);
            this.description(SkillTool.buildDescription(registry.listAll(), null));
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "name", "Skill name to use").required()
            ));
            var tool = new ServerSkillTool();
            build(tool);
            tool.registry = this.registry;
            tool.skillService = this.skillService;
            tool.archiveBuilder = this.archiveBuilder;
            return tool;
        }
    }
}
