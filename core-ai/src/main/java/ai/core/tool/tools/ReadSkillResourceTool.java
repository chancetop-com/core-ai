package ai.core.tool.tools;

import ai.core.skill.SkillLoadException;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillRegistry;
import ai.core.skill.telemetry.SkillTelemetryIdentity;
import ai.core.skill.telemetry.SkillTelemetrySink;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * Tool that lets the agent read a resource file belonging to a loaded skill.
 * Paired with SkillTool: after use_skill(name) returns a resource list,
 * the agent calls read_skill_resource(name, path) to get file contents.
 *
 * @author xander
 */
public class ReadSkillResourceTool extends ToolCall {

    public static final String TOOL_NAME = "read_skill_resource";

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadSkillResourceTool.class);

    private static final String DESC = """
            Read a resource file that belongs to a skill (e.g. references/*.md, scripts/*.py).
            Use AFTER calling use_skill(name) to access files listed in the skill's Resources section.
            Path is relative to the skill directory; absolute paths or '..' are not allowed.
            """;

    public static Builder builder() {
        return new Builder();
    }

    private SkillRegistry registry;
    private SkillTelemetrySink telemetrySink = SkillTelemetrySink.NOOP;
    private Function<SkillMetadata, SkillTelemetryIdentity> identityResolver;

    @Override
    public ToolCallResult execute(String arguments) {
        long startTime = System.currentTimeMillis();
        try {
            var args = parseArguments(arguments);
            String name = getStringValue(args, "name");
            String path = getStringValue(args, "path");
            if (name == null || path == null || name.isBlank() || path.isBlank()) {
                return ToolCallResult.failed("'name' and 'path' are required")
                    .withDuration(System.currentTimeMillis() - startTime);
            }
            var skill = registry != null ? registry.find(name.trim()) : null;
            if (skill == null) {
                return ToolCallResult.failed("skill not found: " + name)
                    .withDuration(System.currentTimeMillis() - startTime);
            }
            var content = registry.readResource(skill, path.trim());
            telemetrySink.recordResourceRead(resolveIdentity(skill));
            return ToolCallResult.completed(content)
                .withDuration(System.currentTimeMillis() - startTime);
        } catch (SkillLoadException e) {
            LOGGER.warn("failed to read skill resource", e);
            return ToolCallResult.failed("read failed: " + e.getMessage())
                .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Failed: " + e.getMessage())
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private SkillTelemetryIdentity resolveIdentity(SkillMetadata skill) {
        if (identityResolver != null) {
            return identityResolver.apply(skill);
        }
        return new SkillTelemetryIdentity(skill.getSkillId(), skill.getQualifiedName(),
                skill.getDigest() == null ? "" : skill.getDigest());
    }

    public static class Builder extends ToolCall.Builder<Builder, ReadSkillResourceTool> {
        private SkillRegistry registry;
        private SkillTelemetrySink telemetrySink = SkillTelemetrySink.NOOP;
        private Function<SkillMetadata, SkillTelemetryIdentity> identityResolver;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder registry(SkillRegistry registry) {
            this.registry = registry;
            return this;
        }

        public Builder telemetrySink(SkillTelemetrySink telemetrySink) {
            this.telemetrySink = telemetrySink != null ? telemetrySink : SkillTelemetrySink.NOOP;
            return this;
        }

        public Builder identityResolver(Function<SkillMetadata, SkillTelemetryIdentity> identityResolver) {
            this.identityResolver = identityResolver;
            return this;
        }

        public ReadSkillResourceTool build() {
            this.name(TOOL_NAME);
            this.description(DESC);
            this.parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "name", "Skill qualified name").required(),
                ToolCallParameters.ParamSpec.of(String.class, "path", "Resource path relative to skill dir").required()
            ));
            var tool = new ReadSkillResourceTool();
            build(tool);
            tool.registry = this.registry;
            tool.telemetrySink = this.telemetrySink;
            tool.identityResolver = this.identityResolver;
            return tool;
        }
    }
}
