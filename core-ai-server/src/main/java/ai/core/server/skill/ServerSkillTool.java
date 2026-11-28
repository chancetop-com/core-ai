package ai.core.server.skill;

import ai.core.agent.ExecutionContext;
import ai.core.sandbox.Sandbox;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.skill.telemetry.SkillTelemetryBuffer;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillRegistry;
import ai.core.skill.SkillSdkCapabilityManifest;
import ai.core.session.CustomEventEmitter;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.tool.tools.SkillTool;
import ai.core.utils.JsonUtil;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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

    public static final String ERROR_NOT_ATTACHED = "SKILL_NOT_ATTACHED";
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerSkillTool.class);
    private static final String SANDBOX_SKILL_BASE = "/skill";
    private static final String SANDBOX_HUB_HINT = """

            Scripts of this skill run inside the sandbox. Call this session's configured MCP servers, API tools,
            LLM_CALL definitions, sub-agents and non-sandboxed builtin tools through the sandbox hub instead of
            hardcoding endpoints or credentials: `from core_ai_session import session` (python) or
            `core-ai-sandbox catalog|describe|call` (bash). Check `core-ai-sandbox catalog` for what this agent
            has attached before writing calls. Arguments are keyword arguments matching the tool's
            `input_schema`, `.data` holds the response parsed as JSON, and a failed call raises `ToolError` in
            python (the CLI exits non-zero) instead of returning an error result; there is no direct LLM access
            in a sandbox script.
            """;

    public static Builder builder() {
        return new Builder();
    }

    private SkillRegistry registry;
    private SkillService skillService;
    private SkillArchiveBuilder archiveBuilder;
    private SkillTelemetryBuffer telemetryBuffer;

    /** Compute description dynamically so that skills attached to the session after
     *  the tool was built (via AgentSessionManager.loadSkills) become visible to the LLM. */
    @Override
    public String getDescription() {
        return SkillTool.buildDescription(registry != null ? registry.listAll() : List.of(), null);
    }

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
                emitNotAttachedCard(context, name.trim());
                return ToolCallResult.failed(ERROR_NOT_ATTACHED + ": Skill '" + name.trim()
                                + "' is not attached to this session. Ask the user to attach it before calling use_skill. Available: "
                                + skillNameList())
                        .withDuration(System.currentTimeMillis() - startTime);
            }

            var definition = skillService.findByQualifiedName(meta.getQualifiedName());
            var sandbox = context.getSandbox();
            var baseDir = materializeIfPossible(sandbox, definition);

            var content = renderSkill(meta, definition, baseDir);
            emitCapabilities(context, meta, definition);
            if (telemetryBuffer != null) {
                telemetryBuffer.recordInvoke(definition.id, definition.digest);
            }
            if (sandbox != null && baseDir == null) {
                content += "\n\n[Sandbox unavailable: the skill content is loaded, but scripts cannot run until a sandbox is available.]";
            }

            return ToolCallResult.completed(content)
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
        } catch (RuntimeException e) {
            if (telemetryBuffer != null) {
                telemetryBuffer.recordMaterializeFail(def.id, def.digest);
            }
            LOGGER.warn("skill materialization failed, returning content without sandbox base_dir: {}, error={}",
                    def.qualifiedName, e.getMessage());
            return null;
        }
    }

    private void emitCapabilities(ExecutionContext context, SkillMetadata meta, SkillDefinition def) {
        var emitter = context.getCustomVariable(CustomEventEmitter.CONTEXT_KEY, CustomEventEmitter.class);
        if (emitter == null) return;
        var manifest = SkillSdkCapabilityManifest.parse(def.allowedTools, def.metadata);
        if (!manifest.enabled() || manifest.entries().isEmpty()) return;
        var items = new ArrayList<Map<String, String>>();
        for (var entry : manifest.entries()) {
            items.add(Map.of("label", entry.kind(), "value", entry.displayPath()));
        }
        var card = JsonUtil.toJson(Map.of(
                "schema_version", 1,
                "title", "Skill · " + meta.getQualifiedName(),
                "blocks", List.of(Map.of("type", "key_values", "items", items))));
        var data = JsonUtil.toJson(Map.of(
                "skill", meta.getQualifiedName(),
                "capabilities", manifest.entries().stream().map(e -> e.kind() + ":" + e.displayPath()).toList(),
                "version", manifest.contractVersion()));
        var error = emitter.emit("skill_capabilities", data, card, context.getCurrentToolCallId());
        if (error != null) LOGGER.debug("skill capability card not emitted: {}", error);
    }

    private void emitNotAttachedCard(ExecutionContext context, String requestedSkill) {
        if (context == null) return;
        var emitter = context.getCustomVariable(CustomEventEmitter.CONTEXT_KEY, CustomEventEmitter.class);
        if (emitter == null) return;
        var items = List.of(
                Map.of("label", "Skill", "value", requestedSkill),
                Map.of("label", "Status", "value", "Not attached to this session"),
                Map.of("label", "Action Required", "value", "Attach this skill manually in session settings before retrying use_skill")
        );
        var card = JsonUtil.toJson(Map.of(
                "schema_version", 1,
                "title", "Skill Not Attached · " + requestedSkill,
                "blocks", List.of(Map.of("type", "key_values", "items", items))));
        var data = JsonUtil.toJson(Map.of(
                "qualified_name", requestedSkill,
                "attached", Boolean.FALSE,
                "error_code", ERROR_NOT_ATTACHED));
        var error = emitter.emit("skill_attach_request", data, card, context.getCurrentToolCallId());
        if (error != null) LOGGER.debug("skill attach request card not emitted: {}", error);
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
        sb.append(SkillSdkCapabilityManifest.parse(def.allowedTools, def.metadata).summary());
        if (baseDir != null) {
            sb.append(SANDBOX_HUB_HINT);
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
        private SkillTelemetryBuffer telemetryBuffer;

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

        public Builder telemetryBuffer(SkillTelemetryBuffer telemetryBuffer) {
            this.telemetryBuffer = telemetryBuffer;
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
            tool.telemetryBuffer = this.telemetryBuffer;
            return tool;
        }
    }
}

