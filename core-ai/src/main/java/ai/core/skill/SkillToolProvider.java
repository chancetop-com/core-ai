package ai.core.skill;

import ai.core.tool.ToolCall;
import ai.core.tool.registry.ToolProvider;
import ai.core.tool.tools.SkillTool;

import java.util.Map;

/**
 * Provides the {@link SkillTool} as a {@link ToolProvider}.
 *
 * @author Lim Chen
 */
public class SkillToolProvider implements ToolProvider {
    public static final String SKILL = "skill";

    private final SkillTool tool;

    public SkillToolProvider(SkillConfig skillConfig, String workspaceDir) {
        this.tool = SkillTool.builder()
                .sources(skillConfig.getSources())
                .maxFileSize(skillConfig.getMaxSkillFileSize())
                .workspaceDir(workspaceDir)
                .build();
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
        return Map.of(tool.getName(), tool);
    }
}
