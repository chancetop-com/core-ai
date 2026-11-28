package ai.core.server.skill.quality;

import ai.core.api.server.skill.ActivateSkillQualityPolicyRequest;
import ai.core.api.server.skill.CreateSkillQualityRuleRequest;
import ai.core.api.server.skill.SkillQualityPolicyListResponse;
import ai.core.api.server.skill.SkillQualityPolicyView;
import ai.core.api.server.skill.SkillQualityRuleListResponse;
import ai.core.api.server.skill.SkillQualityRuleTemplateListResponse;
import ai.core.api.server.skill.SkillQualityRuleView;
import ai.core.api.server.skill.TestSkillQualityRuleRequest;
import ai.core.api.server.skill.TestSkillQualityRuleResponse;
import ai.core.api.server.skill.UpdateSkillQualityRuleRequest;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillTier;
import ai.core.server.quality.DecisionSupport;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityRuleKind;
import ai.core.server.quality.domain.QualityRuleLifecycle;
import ai.core.server.skill.SkillQualityFixtureBuilder;
import ai.core.server.skill.SkillStore;
import ai.core.server.skill.SkillTiers;
import ai.core.server.skill.quality.domain.SkillQualityRule;
import ai.core.server.skill.quality.domain.SkillQualityRuleRevision;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Rule governance for the skill gate. Every method re-checks that the caller is a human platform
 * admin; route annotations alone are not enough because API users bypass action permissions.
 *
 * @author core-ai
 */
public class SkillQualityAdminService {
    private static final Pattern KEY_PATTERN = Pattern.compile("^SKILL\\.[A-Z][A-Z0-9_.]{2,80}$");

    @Inject
    SkillQualityPolicyService policyService;
    @Inject
    SkillQualityStore store;
    @Inject
    SkillQualityRuleRegistry registry;
    @Inject
    SkillQualityGate gate;
    @Inject
    SkillStore skillStore;
    @Inject
    SkillQualityFixtureBuilder fixtureBuilder;

    public SkillQualityRuleTemplateListResponse templates(String userId) {
        policyService.requireAdmin(userId);
        var response = new SkillQualityRuleTemplateListResponse();
        response.templates = registry.templates().stream().map(SkillQualityAdminMapper::toTemplate).toList();
        response.inputOptions = SkillQualityRuleRegistry.inputOptions();
        return response;
    }

    public SkillQualityRuleListResponse rules(String userId) {
        return rules(userId, SkillTier.SCOPE_DOMAIN);
    }

    public SkillQualityRuleListResponse rules(String userId, String scope) {
        policyService.requireAdmin(userId);
        var effectiveScope = scope == null || scope.isBlank() ? SkillTier.SCOPE_DOMAIN : SkillTiers.requireScope(scope);
        var active = policyService.active(effectiveScope);
        var enforcementByRule = new LinkedHashMap<String, QualityEnforcement>();
        for (var rule : active.rules()) enforcementByRule.put(rule.revision().ruleId, rule.enforcement());

        var response = new SkillQualityRuleListResponse();
        response.policyRevision = active.revision();
        response.rules = store.listRules().stream()
            .filter(rule -> rule.lifecycle != QualityRuleLifecycle.RETIRED)
            .map(rule -> toView(rule, enforcementByRule.get(rule.id)))
            .toList();
        return response;
    }

    public SkillQualityRuleView createRule(CreateSkillQualityRuleRequest request, String userId) {
        policyService.requireAdmin(userId);
        if (request.key == null || !KEY_PATTERN.matcher(request.key).matches()) {
            throw new BadRequestException("skill quality rule key must match " + KEY_PATTERN.pattern());
        }
        if (store.findRuleByKey(request.key).isPresent()) {
            throw new BadRequestException("skill quality rule key already exists: " + request.key);
        }
        validateCustomRule(request);
        var now = ZonedDateTime.now();
        var rule = new SkillQualityRule();
        rule.id = "sqrule:" + UUID.randomUUID();
        rule.key = request.key;
        rule.name = request.name;
        rule.category = request.category == null ? "CUSTOM" : request.category;
        rule.builtIn = Boolean.FALSE;
        rule.latestRevision = 1;
        rule.lifecycle = QualityRuleLifecycle.DRAFT;
        rule.createdBy = userId;
        rule.createdAt = now;
        rule.updatedBy = userId;
        rule.updatedAt = now;
        store.insertRule(rule);
        store.insertRevision(revision(rule, 1, request, userId, "create custom rule"));
        return toView(rule, null);
    }

    public SkillQualityRuleView updateRule(String ruleId, UpdateSkillQualityRuleRequest request, String userId) {
        policyService.requireAdmin(userId);
        var rule = policyService.requireRule(ruleId);
        if (Boolean.TRUE.equals(rule.builtIn)) {
            throw new BadRequestException("built-in skill quality rules are defined in code; only enforcement can change");
        }
        if (rule.lifecycle == QualityRuleLifecycle.RETIRED) {
            throw new BadRequestException("retired skill quality rule cannot be edited");
        }
        var latest = policyService.requireRevision(ruleId, rule.latestRevision);
        var nextRevision = rule.latestRevision + 1;
        var entity = copy(latest, rule, nextRevision);
        if (request.name != null) entity.name = request.name;
        if (request.category != null) entity.category = request.category;
        if (request.description != null) entity.description = request.description;
        if (request.rationale != null) entity.rationale = request.rationale;
        if (request.inputSelector != null) entity.inputSelector = List.copyOf(request.inputSelector);
        if (request.decisionType != null) entity.decisionType = request.decisionType;
        if (request.decisionQuestion != null) entity.decisionQuestion = request.decisionQuestion;
        if (request.decisionInstruction != null) entity.decisionInstruction = request.decisionInstruction;
        if (request.decisionCriteria != null) entity.decisionCriteria = new LinkedHashMap<>(request.decisionCriteria);
        if (request.decisionLevels != null) entity.decisionLevels = List.copyOf(request.decisionLevels);
        if (request.decisionPolarity != null) entity.decisionPolarity = request.decisionPolarity;
        if (request.affirmativeThreshold != null) entity.affirmativeThreshold = request.affirmativeThreshold;
        if (request.negativeThreshold != null) entity.negativeThreshold = request.negativeThreshold;
        if (request.modelAlias != null) entity.modelAlias = request.modelAlias;
        if (request.expectedRouteFingerprint != null) {
            entity.expectedRouteFingerprint = request.expectedRouteFingerprint;
        }
        if (request.messageTemplate != null) entity.messageTemplate = request.messageTemplate;
        if (request.remediationTemplate != null) entity.remediationTemplate = request.remediationTemplate;
        if (request.parameters != null) entity.parameters = new LinkedHashMap<>(request.parameters);
        entity.changeReason = request.changeReason;
        entity.createdBy = userId;
        entity.createdAt = ZonedDateTime.now();
        entity.tested = Boolean.FALSE;
        entity.testedAt = null;
        entity.testSummary = null;
        validateRevision(entity);
        store.insertRevision(entity);

        rule.latestRevision = nextRevision;
        rule.lifecycle = QualityRuleLifecycle.DRAFT;
        rule.updatedBy = userId;
        rule.updatedAt = ZonedDateTime.now();
        rule.lastTestedRevision = null;
        rule.lastTestedAt = null;
        rule.lastTestSummary = null;
        store.replaceRule(rule);
        return toView(rule, null);
    }

    public TestSkillQualityRuleResponse testRule(String ruleId, TestSkillQualityRuleRequest request, String userId) {
        policyService.requireAdmin(userId);
        var rule = policyService.requireRule(ruleId);
        var revision = policyService.requireRevision(ruleId, rule.latestRevision);
        SkillDefinition skill = skillStore.get(request.skillId)
            .orElseThrow(() -> new NotFoundException("skill not found, id=" + request.skillId));
        var candidate = fixtureBuilder.build(skill, request.skillMd);
        var enforcement = enforcementOf(candidate.targetTier.scope(), rule.id);
        long started = System.currentTimeMillis();
        var evaluation = gate.testRule(candidate, revision, enforcement);
        var response = new TestSkillQualityRuleResponse();
        response.ruleId = rule.key;
        response.ruleRevision = revision.revision;
        response.result = evaluation.status().name();
        response.issues = SkillQualityReportMapper.issueViews(evaluation.issues(), true);
        response.durationMs = System.currentTimeMillis() - started;
        response.routeFingerprint = evaluation.routeFingerprint();

        var expected = request.expectedResult;
        boolean matches = expected == null || expected.isBlank() || expected.equalsIgnoreCase(response.result);
        response.matchesExpected = matches;
        rule.lastTestedRevision = revision.revision;
        rule.lastTestedAt = ZonedDateTime.now();
        rule.lastTestSummary = "expected=" + (expected == null ? "any" : expected) + ", actual=" + response.result
            + ", matched=" + matches + (request.sampleName == null ? "" : ", sample=" + request.sampleName);
        if (matches && rule.lifecycle == QualityRuleLifecycle.DRAFT) {
            rule.lifecycle = QualityRuleLifecycle.TESTED;
        }
        rule.updatedBy = userId;
        rule.updatedAt = ZonedDateTime.now();
        store.replaceRule(rule);
        response.lifecycle = rule.lifecycle.name();
        return response;
    }

    public SkillQualityRuleView retireRule(String ruleId, String userId) {
        policyService.requireAdmin(userId);
        policyService.retireRule(ruleId, userId);
        return toView(policyService.requireRule(ruleId), null);
    }

    public SkillQualityPolicyListResponse policies(String userId) {
        return policies(userId, SkillTier.SCOPE_DOMAIN);
    }

    public SkillQualityPolicyListResponse policies(String userId, String scope) {
        policyService.requireAdmin(userId);
        if (scope == null || scope.isBlank()) {
            throw new BadRequestException("scope query parameter is required (use a tier scope or 'all')");
        }
        var effectiveScope = scope.trim();
        var response = new SkillQualityPolicyListResponse();
        if ("all".equalsIgnoreCase(effectiveScope)) {
            var domainActive = policyService.active(SkillTier.SCOPE_DOMAIN);
            response.active = SkillQualityAdminMapper.toPolicyView(domainActive.policy(), domainActive.generation(), true);
            response.scopes = SkillTiers.ALL_SCOPES.stream()
                .map(policyService::active)
                .map(active -> SkillQualityAdminMapper.toPolicyView(active.policy(), active.generation(), true))
                .toList();
            response.history = policyService.historyAllTierScopes().stream()
                .map(policy -> {
                    var head = policyService.requireHead(policy.scope);
                    return SkillQualityAdminMapper.toPolicyView(policy, policy.generation,
                        policy.revision.equals(head.activeRevision));
                })
                .toList();
            return response;
        }
        var normalized = SkillTiers.requireScope(effectiveScope);
        var active = policyService.active(normalized);
        response.active = SkillQualityAdminMapper.toPolicyView(active.policy(), active.generation(), true);
        response.history = policyService.history(normalized).stream()
            .map(policy -> SkillQualityAdminMapper.toPolicyView(policy, policy.generation, policy.revision == active.revision()))
            .toList();
        return response;
    }

    public SkillQualityPolicyView activate(ActivateSkillQualityPolicyRequest request, String userId) {
        policyService.requireAdmin(userId);
        if (request.changeReason == null || request.changeReason.isBlank()) {
            throw new BadRequestException("change_reason is required");
        }
        if (request.scope == null || request.scope.isBlank()) {
            throw new BadRequestException("scope is required");
        }
        var scope = SkillTiers.requireScope(request.scope);
        QualityEnforcement enforcement = null;
        if (request.enforcement != null) {
            try {
                enforcement = QualityEnforcement.valueOf(request.enforcement);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("invalid enforcement: " + request.enforcement, "BAD_REQUEST", e);
            }
            if (enforcement != QualityEnforcement.SHADOW && request.ruleRevision != null
                && request.ruleId != null) {
                var rule = policyService.requireRule(request.ruleId);
                var revision = policyService.requireRevision(request.ruleId, request.ruleRevision);
                requireTested(rule, revision);
            }
        }
        var command = new SkillQualityPolicyService.ActivateCommand(request.policyRevision,
            request.ruleId, request.ruleRevision, enforcement, request.expectedGeneration,
            request.changeReason, request.suspend, request.resume, request.exemptions);
        var policy = policyService.createPolicy(scope, command, userId);
        var head = policyService.requireHead(scope);
        return SkillQualityAdminMapper.toPolicyView(policy, head.generation, true);
    }

    public void requireTested(SkillQualityRule rule, SkillQualityRuleRevision revision) {
        if (Boolean.TRUE.equals(rule.builtIn)) return;
        if (rule.lastTestedRevision == null || !rule.lastTestedRevision.equals(revision.revision)) {
            throw new BadRequestException("rule revision must be tested before it can block publishes");
        }
    }

    private QualityEnforcement enforcementOf(String scope, String ruleId) {
        for (var active : policyService.active(scope).rules()) {
            if (ruleId.equals(active.revision().ruleId)) return active.enforcement();
        }
        return QualityEnforcement.SHADOW;
    }

    private SkillQualityRuleRevision revision(SkillQualityRule rule, int revision,
                                              CreateSkillQualityRuleRequest request, String userId,
                                              String changeReason) {
        var entity = new SkillQualityRuleRevision();
        entity.id = rule.id + ":" + revision;
        entity.ruleId = rule.id;
        entity.ruleKey = rule.key;
        entity.revision = revision;
        entity.name = request.name;
        entity.category = rule.category;
        entity.description = request.description;
        entity.rationale = request.rationale;
        entity.kind = QualityRuleKind.DECISION;
        entity.evaluatorKey = SkillDecisionQualityRule.EVALUATOR_KEY;
        entity.evaluatorVersion = 1;
        entity.parameters = request.parameters == null ? Map.of() : new LinkedHashMap<>(request.parameters);
        entity.inputSelector = request.inputSelector == null ? List.of(SkillCustomRuleInput.DESCRIPTION)
            : List.copyOf(request.inputSelector);
        entity.decisionType = request.decisionType == null ? "noul" : request.decisionType;
        entity.decisionQuestion = request.decisionQuestion;
        entity.decisionInstruction = request.decisionInstruction;
        entity.decisionCriteria = request.decisionCriteria == null ? null
            : new LinkedHashMap<>(request.decisionCriteria);
        entity.decisionLevels = request.decisionLevels == null ? null : List.copyOf(request.decisionLevels);
        entity.decisionPolarity = request.decisionPolarity == null
            ? DecisionSupport.AFFIRMATIVE_MEANS_COMPLIANT : request.decisionPolarity;
        entity.affirmativeThreshold = request.affirmativeThreshold;
        if (entity.affirmativeThreshold == null) entity.affirmativeThreshold = 0.80;
        entity.negativeThreshold = request.negativeThreshold;
        if (entity.negativeThreshold == null) entity.negativeThreshold = 0.20;
        entity.modelAlias = request.modelAlias;
        entity.expectedRouteFingerprint = request.expectedRouteFingerprint;
        entity.messageTemplate = request.messageTemplate;
        entity.remediationTemplate = request.remediationTemplate;
        entity.tested = Boolean.FALSE;
        entity.changeReason = changeReason;
        entity.createdBy = userId;
        entity.createdAt = ZonedDateTime.now();
        validateRevision(entity);
        return entity;
    }

    private SkillQualityRuleRevision copy(SkillQualityRuleRevision source, SkillQualityRule rule, int revision) {
        var entity = new SkillQualityRuleRevision();
        entity.id = rule.id + ":" + revision;
        entity.ruleId = rule.id;
        entity.ruleKey = rule.key;
        entity.revision = revision;
        entity.name = source.name;
        entity.category = source.category;
        entity.description = source.description;
        entity.rationale = source.rationale;
        entity.kind = source.kind;
        entity.evaluatorKey = source.evaluatorKey;
        entity.evaluatorVersion = source.evaluatorVersion;
        entity.parameters = source.parameters == null ? Map.of() : new LinkedHashMap<>(source.parameters);
        entity.inputSelector = source.inputSelector == null ? List.of() : List.copyOf(source.inputSelector);
        entity.decisionType = source.decisionType;
        entity.decisionQuestion = source.decisionQuestion;
        entity.decisionInstruction = source.decisionInstruction;
        entity.decisionCriteria = source.decisionCriteria == null ? null
            : new LinkedHashMap<>(source.decisionCriteria);
        entity.decisionLevels = source.decisionLevels == null ? null : List.copyOf(source.decisionLevels);
        entity.decisionPolarity = source.decisionPolarity;
        entity.affirmativeThreshold = source.affirmativeThreshold;
        entity.negativeThreshold = source.negativeThreshold;
        entity.modelAlias = source.modelAlias;
        entity.expectedRouteFingerprint = source.expectedRouteFingerprint;
        entity.messageTemplate = source.messageTemplate;
        entity.remediationTemplate = source.remediationTemplate;
        return entity;
    }

    private void validateCustomRule(CreateSkillQualityRuleRequest request) {
        if (!registry.isDecisionComposable()) throw new BadRequestException("DECISION evaluator is not available");
        if (request.name == null || request.name.isBlank()) throw new BadRequestException("rule name is required");
        if (request.description == null || request.description.isBlank()) {
            throw new BadRequestException("rule requirement (description) is required");
        }
        if (request.decisionQuestion == null || request.decisionQuestion.isBlank()) {
            throw new BadRequestException("decision question is required");
        }
        if (request.messageTemplate == null || request.messageTemplate.isBlank()) {
            throw new BadRequestException("failure message is required");
        }
        if (request.remediationTemplate == null || request.remediationTemplate.isBlank()) {
            throw new BadRequestException("remediation suggestion is required");
        }
        Double affirmative = request.affirmativeThreshold;
        if (affirmative == null) affirmative = 0.80;
        Double negative = request.negativeThreshold;
        if (negative == null) negative = 0.20;
        validateThresholds(affirmative, negative);
        if (request.inputSelector != null) {
            for (var input : request.inputSelector) {
                if (!SkillQualityRuleRegistry.inputOptions().contains(input)) {
                    throw new BadRequestException("unsupported skill rule input: " + input);
                }
            }
        }
    }

    private void validateRevision(SkillQualityRuleRevision revision) {
        if (revision.description == null || revision.description.isBlank()) {
            throw new BadRequestException("rule requirement (description) is required");
        }
        if (revision.decisionQuestion == null || revision.decisionQuestion.isBlank()) {
            throw new BadRequestException("decision question is required");
        }
        if (revision.messageTemplate == null || revision.messageTemplate.isBlank()) {
            throw new BadRequestException("failure message is required");
        }
        if (revision.remediationTemplate == null || revision.remediationTemplate.isBlank()) {
            throw new BadRequestException("remediation suggestion is required");
        }
        validateThresholds(revision.affirmativeThreshold, revision.negativeThreshold);
        if (revision.inputSelector != null) {
            for (var input : revision.inputSelector) {
                if (!SkillQualityRuleRegistry.inputOptions().contains(input)) {
                    throw new BadRequestException("unsupported skill rule input: " + input);
                }
            }
        }
    }

    private void validateThresholds(Double affirmative, Double negative) {
        double upper = affirmative == null ? 0.80 : affirmative;
        double lower = negative == null ? 0.20 : negative;
        if (lower < 0 || lower >= upper || upper > 1) {
            throw new BadRequestException("thresholds must satisfy 0 <= lower < upper <= 1");
        }
    }


    private SkillQualityRuleView toView(SkillQualityRule rule, QualityEnforcement enforcement) {
        return SkillQualityAdminMapper.toView(rule, store.getRevision(rule.id, rule.latestRevision).orElse(null),
            enforcement, registry);
    }
}
