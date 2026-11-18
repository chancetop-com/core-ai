package ai.core.server.skill.quality;

import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.StoredQualityIssue;

import java.util.ArrayList;

/**
 * The eval dataset is a published contract, not something the gate executes. Missing files, schema errors,
 * dimension gaps and fabricated placeholders block the publish.
 *
 * @author core-ai
 */
public final class SkillEvalRule implements SkillQualityRule {
    private static final SkillRuleMeta META = new SkillRuleMeta(
        "SKILL.EVAL_DATASET", 1, "Eval dataset",
        "eval/eval.json must be published, cover all 7 dimensions and contain observable assertions.", "EVAL",
        QualityEnforcement.BLOCK);
    private final SkillEvalDatasetValidator validator = new SkillEvalDatasetValidator();

    @Override
    public SkillRuleMeta meta() {
        return META;
    }

    @Override
    public SkillRuleResult evaluate(SkillPublishCandidate candidate) {
        var validation = validator.validate(candidate);
        var issues = new ArrayList<StoredQualityIssue>();
        for (var problem : validation.fatal()) {
            issues.add(SkillQualityIssues.issue(META, QualityIssueStatus.FAIL, problem.fieldPath(),
                problem.reason(), problem.message(), problem.suggestion()));
        }
        if (!issues.isEmpty()) return new SkillRuleResult(QualityIssueStatus.FAIL, issues);
        return SkillRuleResult.pass();
    }
}
