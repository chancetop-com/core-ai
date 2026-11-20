package ai.core.server.quality;

import ai.core.decision.DecisionAnswer;
import ai.core.decision.DecisionQuestion;
import ai.core.decision.DecisionRoute;
import ai.core.decision.DecisionType;
import ai.core.server.quality.domain.AgentQualityRuleRevision;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityIssueStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FunctionSummaryRuleTest {
    @Test
    void revisionInstructionOverridesTheBuiltInDefault() {
        var captured = new AtomicReference<List<DecisionQuestion>>();
        var evaluation = new FunctionSummaryRule().evaluate(context("分析销售数据并生成周报", "只要求大致说明用途即可", captured));

        assertEquals(QualityIssueStatus.PASS, evaluation.status());
        assertEquals("只要求大致说明用途即可", captured.get().get(0).instructions());
    }

    @Test
    void blankOverrideFallsBackToTheBuiltInInstruction() {
        var captured = new AtomicReference<List<DecisionQuestion>>();
        new FunctionSummaryRule().evaluate(context("分析销售数据并生成周报", "  ", captured));

        assertEquals(new FunctionSummaryRule().template().questions().get(0).instruction(),
            captured.get().get(0).instructions());
    }

    private QualityRuleContext context(String description, String instructionOverride,
                                       AtomicReference<List<DecisionQuestion>> captured) {
        var revision = new AgentQualityRuleRevision();
        revision.ruleKey = FunctionSummaryRule.KEY;
        revision.revision = 2;
        revision.name = "description 必须包含功能简介";
        revision.description = "description 必须让读者明确知道 Agent 的至少一项实际功能。";
        revision.decisionInstruction = instructionOverride;
        revision.affirmativeThreshold = 0.80;
        revision.negativeThreshold = 0.20;

        var candidate = new PublishCandidate(null, null, "agent", description, null, null, 0, null, null, null, null);
        var snapshot = new PublishQualitySnapshot(candidate, List.of(), null, null, null);
        var gateway = new QualityDecisionGateway() {
            @Override
            public ValidatedDecision decide(String modelAlias, String state, List<DecisionQuestion> questions,
                                            String expectedRouteFingerprint, QualityBudget budget) {
                captured.set(questions);
                return new ValidatedDecision(
                    Map.of(FunctionSummaryRule.QUESTION, new DecisionAnswer(FunctionSummaryRule.QUESTION,
                        DecisionType.NOUL, 0.90, null, null, null, null, null)),
                    questions, new DecisionRoute("provider", "upstream", "route-v1"), "upstream", 1, 1);
            }
        };
        return new QualityRuleContext(snapshot, revision, QualityEnforcement.SHADOW, QualityBudget.forTest(), gateway);
    }
}
