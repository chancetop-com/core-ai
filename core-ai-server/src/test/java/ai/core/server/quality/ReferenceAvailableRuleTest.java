package ai.core.server.quality;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.quality.domain.AgentQualityRuleRevision;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityIssueStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceAvailableRuleTest {
    private final ReferenceAvailableRule rule = new ReferenceAvailableRule();

    @Test
    void missingSkillProducesOneLocatableFailure() {
        var result = evaluate(List.of(new ResolvedReference("SKILL", "skill-1", null, null,
            QualityReferenceStatus.MISSING, "SKILL_NOT_FOUND", "/skill_ids[2]", 2)));

        assertEquals(QualityIssueStatus.FAIL, result.status());
        var issue = result.issues().get(0);
        assertEquals("CFG.REFERENCE_AVAILABLE", issue.ruleId);
        assertEquals("/skill_ids[2]", issue.fieldPath);
        assertEquals("SKILL", issue.resourceType);
        assertEquals("skill-1", issue.resourceId);
        assertEquals("EDIT_INPUT", issue.recommendedAction.name());
    }

    @Test
    void databaseErrorIsErrorNotDeleted() {
        var result = evaluate(List.of(new ResolvedReference("DATASET", "dataset-1", null, null,
            QualityReferenceStatus.ERROR, "REFERENCE_RESOLUTION_ERROR", "/dataset_config[0]", 0)));

        assertEquals(QualityIssueStatus.ERROR, result.status());
        assertEquals("CONTACT_ADMIN", result.issues().get(0).recommendedAction.name());
    }

    @Test
    void mcpReferencesAreLeftToTheDedicatedRule() {
        var result = evaluate(List.of(new ResolvedReference("MCP", "server-1", null, null,
            QualityReferenceStatus.MISSING, "MCP_SERVER_NOT_FOUND", "/tools[0]", 0)));

        assertEquals(QualityIssueStatus.PASS, result.status());
        assertTrue(result.issues().isEmpty());
    }

    @Test
    void allAvailablePasses() {
        var result = evaluate(List.of(new ResolvedReference("SKILL", "skill-1", null, "Skill One",
            QualityReferenceStatus.AVAILABLE, null, "/skill_ids[0]", 0)));
        assertEquals(QualityIssueStatus.PASS, result.status());
    }

    private QualityRuleEvaluation evaluate(List<ResolvedReference> references) {
        var entity = new AgentDefinition();
        entity.id = "agent";
        var candidate = new PublishCandidate(entity, new AgentPublishedConfig(), "agent", "desc",
            "sha256:c", "sha256:m", 0,
            new QualityPromptView("", null, "INLINE", null, false, false, "sha256:p"),
            List.of(), "/system_prompt", "INLINE");
        var snapshot = new PublishQualitySnapshot(candidate, references,
            new EffectiveCapabilityView(List.of(), Map.of(), Map.of()), "sha256:d", "sha256:r");
        var revision = new AgentQualityRuleRevision();
        revision.ruleId = ReferenceAvailableRule.KEY;
        revision.revision = 1;
        revision.name = "refs";
        revision.category = "CONFIG";
        revision.description = "refs";
        revision.parameters = Map.of();
        return rule.evaluate(new QualityRuleContext(snapshot, revision, QualityEnforcement.BLOCK,
            QualityBudget.forTest(), null));
    }
}
