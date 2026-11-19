package ai.core.server.skill.quality;

import ai.core.api.server.skill.SkillQualityPolicyBindingView;
import ai.core.api.server.skill.SkillQualityPolicyView;
import ai.core.api.server.skill.SkillQualityRuleParameterView;
import ai.core.api.server.skill.SkillQualityRuleQuestionView;
import ai.core.api.server.skill.SkillQualityRuleTemplateView;
import ai.core.api.server.skill.SkillQualityRuleView;
import ai.core.server.quality.QualityRuleEvaluator;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.skill.quality.domain.SkillQualityPolicyRevision;
import ai.core.server.skill.quality.domain.SkillQualityRule;
import ai.core.server.skill.quality.domain.SkillQualityRuleRevision;

import java.util.ArrayList;
import java.util.List;

/**
 * @author core-ai
 */
public final class SkillQualityAdminMapper {
    public static SkillQualityRuleView toView(SkillQualityRule rule, SkillQualityRuleRevision revision,
                                              QualityEnforcement enforcement,
                                              SkillQualityRuleRegistry registry) {
        var view = new SkillQualityRuleView();
        view.id = rule.id;
        view.key = rule.key;
        view.name = revision != null && revision.name != null ? revision.name : rule.name;
        view.category = rule.category;
        view.lifecycle = rule.lifecycle == null ? null : rule.lifecycle.name();
        view.revision = rule.latestRevision;
        view.builtIn = rule.builtIn;
        view.enforcement = enforcement == null ? null : enforcement.name();
        view.updatedBy = rule.updatedBy;
        view.updatedAt = rule.updatedAt;
        view.tested = rule.lastTestedRevision != null && rule.lastTestedRevision.equals(rule.latestRevision);
        view.testedAt = rule.lastTestedAt;
        view.testSummary = rule.lastTestSummary;
        if (revision == null) return view;
        view.description = revision.description;
        view.requirement = revision.description;
        view.rationale = revision.rationale;
        view.kind = revision.kind == null ? null : revision.kind.name();
        view.evaluatorKey = revision.evaluatorKey;
        view.evaluatorVersion = revision.evaluatorVersion;
        view.parameters = revision.parameters;
        view.inputSelector = revision.inputSelector;
        view.decisionType = revision.decisionType;
        view.decisionQuestion = revision.decisionQuestion;
        view.decisionInstruction = revision.decisionInstruction;
        view.decisionCriteria = revision.decisionCriteria;
        view.decisionLevels = revision.decisionLevels;
        view.decisionPolarity = revision.decisionPolarity;
        view.affirmativeThreshold = revision.affirmativeThreshold;
        view.negativeThreshold = revision.negativeThreshold;
        view.modelAlias = revision.modelAlias;
        view.expectedRouteFingerprint = revision.expectedRouteFingerprint;
        view.messageTemplate = revision.messageTemplate;
        view.remediationTemplate = revision.remediationTemplate;
        view.changeReason = revision.changeReason;
        view.editableParameters = parametersOf(revision.evaluatorKey, registry);
        return view;
    }

    public static SkillQualityPolicyView toPolicyView(SkillQualityPolicyRevision policy, Integer generation,
                                                      boolean active) {
        var view = new SkillQualityPolicyView();
        view.revision = policy.revision;
        view.generation = generation;
        view.scope = policy.scope;
        view.active = active;
        view.changeReason = policy.changeReason;
        view.activatedBy = policy.activatedBy;
        view.activatedAt = policy.activatedAt;
        var bindings = new ArrayList<SkillQualityPolicyBindingView>();
        if (policy.bindings != null) {
            for (var binding : policy.bindings) {
                var item = new SkillQualityPolicyBindingView();
                item.ruleId = binding.ruleId;
                item.ruleRevision = binding.ruleRevision;
                item.enforcement = binding.enforcement == null ? null : binding.enforcement.name();
                bindings.add(item);
            }
        }
        view.bindings = bindings;
        view.exemptions = policy.exemptions == null ? List.of() : policy.exemptions;
        return view;
    }

    public static SkillQualityRuleTemplateView toTemplate(QualityRuleEvaluator.RuleTemplate template) {
        var view = new SkillQualityRuleTemplateView();
        view.evaluatorKey = template.evaluatorKey();
        view.name = template.name();
        view.kind = template.kind();
        view.category = template.category();
        view.description = template.description();
        view.supportsDecision = template.supportsDecision();
        view.inputOptions = template.inputOptions();
        var parameters = new ArrayList<SkillQualityRuleParameterView>();
        for (var parameter : template.parameters()) {
            var item = new SkillQualityRuleParameterView();
            item.name = parameter.name();
            item.type = parameter.type();
            item.required = parameter.required();
            item.description = parameter.description();
            item.defaultValue = parameter.defaultValue();
            item.options = parameter.options();
            parameters.add(item);
        }
        view.parameters = parameters;
        var questions = new ArrayList<SkillQualityRuleQuestionView>();
        for (var question : template.questions()) {
            var item = new SkillQualityRuleQuestionView();
            item.name = question.name();
            item.type = question.type();
            item.instruction = question.instruction();
            item.criteria = question.criteria();
            questions.add(item);
        }
        view.questions = questions;
        return view;
    }

    private static List<String> parametersOf(String evaluatorKey, SkillQualityRuleRegistry registry) {
        try {
            registry.require(evaluatorKey);
            return SkillDecisionQualityRule.EVALUATOR_KEY.equals(evaluatorKey)
                ? List.of("passLabels", "minScore") : List.of();
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    private SkillQualityAdminMapper() {
    }
}
