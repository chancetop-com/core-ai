package ai.core.skill;

import ai.core.skill.telemetry.SkillTelemetryIdentity;
import ai.core.skill.telemetry.SkillTelemetrySink;
import ai.core.tool.ToolCall;
import ai.core.tool.registry.ToolProvider;
import ai.core.tool.tools.ReadSkillResourceTool;
import ai.core.tool.tools.SkillTool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Provides the {@link SkillTool} and {@link ReadSkillResourceTool} as a {@link ToolProvider}.
 *
 * @author Lim Chen
 */
public class SkillToolProvider implements ToolProvider {
    public static final String SKILL = "skill";

    private final SkillTool tool;
    private final ReadSkillResourceTool readResourceTool;

    public SkillToolProvider(SkillConfig skillConfig, String workspaceDir) {
        this(skillConfig, workspaceDir, null, null, null);
    }

    public SkillToolProvider(SkillConfig skillConfig, String workspaceDir, SkillTool.SkillAccessGuard accessGuard) {
        this(skillConfig, workspaceDir, accessGuard, null, null);
    }

    public SkillToolProvider(SkillConfig skillConfig, String workspaceDir, SkillTool.SkillAccessGuard accessGuard,
                             SkillTelemetrySink telemetrySink,
                             Function<SkillMetadata, SkillTelemetryIdentity> identityResolver) {
        this.tool = SkillTool.builder()
                .sources(skillConfig.getSources())
                .maxFileSize(skillConfig.getMaxSkillFileSize())
                .workspaceDir(workspaceDir)
                .accessGuard(accessGuard)
                .telemetrySink(telemetrySink)
                .identityResolver(identityResolver)
                .build();
        this.readResourceTool = ReadSkillResourceTool.builder()
                .registry(this.tool.getRegistry())
                .telemetrySink(telemetrySink)
                .identityResolver(identityResolver)
                .build();
    }

    public SkillRegistry registry() {
        return tool.getRegistry();
    }

    @Override
    public String id() {
        return SKILL;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public Map<String, ToolCall> provide() {
        var tools = new LinkedHashMap<String, ToolCall>(2);
        tools.put(tool.getName(), tool);
        tools.put(readResourceTool.getName(), readResourceTool);
        return Map.copyOf(tools);
    }
}
