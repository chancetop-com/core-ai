package ai.core.server.skill.quality;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The five content requirements the gate looks for. Synonyms are normalized heading text, not arbitrary
 * substrings; the table is part of the rule revision.
 *
 * @author core-ai
 */
public final class SkillDisclosureRequirements {
    public static final List<Requirement> ALL = List.of(
        new Requirement("workflow", "Workflow",
            List.of("workflow", "process", "pipeline", "steps", "how it works",
                "工作流", "流程", "步骤")),
        new Requirement("decision", "Decision logic",
            List.of("decision making", "decision logic", "routing", "choose", "branch",
                "决策", "判断", "分支", "如何选择")),
        new Requirement("output", "Output specification",
            List.of("output", "outputs", "output specification", "deliverables", "delivery", "result",
                "输出", "交付", "交付标准", "产物")),
        new Requirement("error", "Error handling",
            List.of("error handling", "errors", "failure modes", "troubleshooting", "recovery",
                "错误处理", "失败处理", "异常", "失败模式")),
        new Requirement("quality", "Quality assurance",
            List.of("quality assurance", "quality", "acceptance", "verification", "checks", "validation", "qc",
                "质量保证", "验收", "质量", "检查"))
    );

    public static Map<String, Requirement> byKey() {
        var result = new LinkedHashMap<String, Requirement>();
        for (var requirement : ALL) result.put(requirement.key(), requirement);
        return result;
    }

    private SkillDisclosureRequirements() {
    }

    public record Requirement(String key, String label, List<String> synonyms) {
        public Requirement {
            synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
        }
    }
}
