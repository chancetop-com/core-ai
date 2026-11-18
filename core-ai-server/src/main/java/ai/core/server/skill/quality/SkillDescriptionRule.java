package ai.core.server.skill.quality;

import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityIssueStatus;

import java.util.regex.Pattern;

/**
 * The description is the only always-loaded discovery signal: it must say when to use the skill and where
 * its scope ends. Structural markers are enforced; the semantic quality of the wording is a later SHADOW rule.
 *
 * @author core-ai
 */
public final class SkillDescriptionRule implements SkillQualityRule {
    private static final SkillRuleMeta META = new SkillRuleMeta(
        "SKILL.DESCRIPTION_TRIGGER_SCOPE", 1, "Description trigger and scope",
        "description must contain the Use when trigger and the scope boundary.", "DISCOVERY",
        QualityEnforcement.BLOCK);
    private static final Pattern USE_WHEN = Pattern.compile("(?i)\\buse\\s+when\\b");
    private static final Pattern SCOPE = Pattern.compile(
        "(?i)\\b(scope|in[-\\s]?scope|out[-\\s]?of[-\\s]?scope|not\\s+for|do\\s+not\\s+use|only\\s+for)\\b"
            + "|适用范围|不适用|仅用于|不用于");

    @Override
    public SkillRuleMeta meta() {
        return META;
    }

    @Override
    public SkillRuleResult evaluate(SkillPublishCandidate candidate) {
        var description = candidate.description == null ? "" : candidate.description.trim();
        if (description.isEmpty() || description.length() > 4096) {
            return fail("DESCRIPTION_LENGTH", "description 为空或超过 4096 字符。",
                "写一句说明做什么、何时使用、不覆盖什么。");
        }
        if (!USE_WHEN.matcher(description).find()) {
            return fail("MISSING_USE_WHEN", "description 缺少 Use when 触发条件。",
                "加入 “Use when the user asks to …”，点名具体请求或输入类型。");
        }
        if (!SCOPE.matcher(description).find()) {
            return fail("MISSING_SCOPE", "description 没有说明 scope 边界。",
                "加入 “Scope: …; not for …”，说明不处理的任务或输入。");
        }
        return SkillRuleResult.pass();
    }

    private SkillRuleResult fail(String reason, String message, String suggestion) {
        return SkillRuleResult.fail(SkillQualityIssues.issue(META, QualityIssueStatus.FAIL,
            "/description", reason, message, suggestion));
    }
}
