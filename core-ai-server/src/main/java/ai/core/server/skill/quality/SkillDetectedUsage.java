package ai.core.server.skill.quality;

import java.util.List;

/**
 * One capability use found in a skill package. {@code path} is kind-specific: MCP server/tool,
 * api-tool app/service/operation, tool name, subagent name, dataset name.
 *
 * @author core-ai
 */
public record SkillDetectedUsage(String kind, List<String> path, String file, int line,
                                 boolean dynamic, String snippet) {
    public SkillDetectedUsage {
        path = path == null ? List.of() : List.copyOf(path);
    }

    public String joinedPath() {
        return String.join("/", path);
    }
}
