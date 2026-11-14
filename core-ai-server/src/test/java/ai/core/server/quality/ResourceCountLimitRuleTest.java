package ai.core.server.quality;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.quality.domain.AgentQualityRuleRevision;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityIssueStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceCountLimitRuleTest {
    private final ResourceCountLimitRule rule = new ResourceCountLimitRule();

    @Test
    void fiftySkillsPassAndFiftyOneFail() {
        var fifty = evaluate(50, params("skills", "50"));
        assertEquals(QualityIssueStatus.PASS, fifty.status());

        var fiftyOne = evaluate(51, params("skills", "50"));
        assertEquals(QualityIssueStatus.FAIL, fiftyOne.status());
        var issue = fiftyOne.issues().get(0);
        assertEquals(51, issue.actualCount);
        assertEquals(50, issue.maxCount);
        assertEquals("/skill_ids", issue.fieldPath);
    }

    @Test
    void countIsDistinctByTypeIdAndSource() {
        var config = new AgentPublishedConfig();
        config.tools = List.of(
            ToolRef.of("a", ToolSourceType.BUILTIN, null),
            ToolRef.of("a", ToolSourceType.BUILTIN, null),
            ToolRef.of("a", ToolSourceType.BUILTIN, "source-2"),
            ToolRef.of("b", ToolSourceType.API, null));
        var result = evaluateConfig(config, params("tool_refs_total", "2"));
        assertEquals(QualityIssueStatus.FAIL, result.status());
        assertEquals(3, result.issues().get(0).actualCount);
        assertTrue(result.issues().get(0).message.contains("max 2"));
    }

    @Test
    void zeroIsAnAllowedExplicitBan() {
        var result = evaluate(1, params("skills", "0"));
        assertEquals(QualityIssueStatus.FAIL, result.status());
        assertEquals(0, result.issues().get(0).maxCount);
    }

    @Test
    void invalidParameterIsErrorNotFailure() {
        var result = evaluate(1, params("skills", "-1"));
        assertEquals(QualityIssueStatus.ERROR, result.status());
    }

    private QualityRuleEvaluation evaluate(int skills, Map<String, String> params) {
        var config = new AgentPublishedConfig();
        var ids = new ArrayList<String>();
        for (int i = 0; i < skills; i++) ids.add("skill-" + i);
        config.skillIds = ids;
        return evaluateConfig(config, params);
    }

    private QualityRuleEvaluation evaluateConfig(AgentPublishedConfig config, Map<String, String> params) {
        var entity = new AgentDefinition();
        entity.id = "agent";
        var candidate = new PublishCandidate(entity, config, "agent", "desc", "sha256:c", "sha256:m", 0,
            new QualityPromptView("", null, "INLINE", null, false, false, "sha256:p"),
            List.of(), "/system_prompt", "INLINE");
        var snapshot = new PublishQualitySnapshot(candidate, List.of(),
            new EffectiveCapabilityView(List.of(), Map.of(), Map.of()), "sha256:d", "sha256:r");
        var revision = new AgentQualityRuleRevision();
        revision.ruleId = ResourceCountLimitRule.KEY;
        revision.revision = 1;
        revision.name = "count";
        revision.category = "CONFIG";
        revision.description = "count limit";
        revision.parameters = params;
        revision.messageTemplate = "{{category}} is {{actual_count}}, max {{max_count}}";
        revision.remediationTemplate = "remove some {{category}}";
        return rule.evaluate(new QualityRuleContext(snapshot, revision, QualityEnforcement.BLOCK,
            QualityBudget.forTest(), null));
    }

    private Map<String, String> params(String name, String value) {
        return Map.of(name, value);
    }
}
