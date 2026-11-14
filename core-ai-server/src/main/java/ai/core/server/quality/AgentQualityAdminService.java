package ai.core.server.quality;
import ai.core.server.agent.AgentDefinitionStore;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;

import ai.core.api.server.agent.ActivateAgentQualityPolicyRequest;
import ai.core.api.server.agent.AgentQualityPolicyBindingView;
import ai.core.api.server.agent.AgentQualityPolicyListResponse;
import ai.core.api.server.agent.AgentQualityPolicyView;
import ai.core.api.server.agent.AgentQualityRuleListResponse;
import ai.core.api.server.agent.AgentQualityRuleParameterView;
import ai.core.api.server.agent.AgentQualityRuleTemplateListResponse;
import ai.core.api.server.agent.AgentQualityRuleTemplateView;
import ai.core.api.server.agent.AgentQualityRuleView;
import ai.core.api.server.agent.CreateAgentQualityRuleRequest;
import ai.core.api.server.agent.TestAgentQualityRuleRequest;
import ai.core.api.server.agent.TestAgentQualityRuleResponse;
import ai.core.api.server.agent.UpdateAgentQualityRuleRequest;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.quality.domain.AgentQualityPolicyRevision;
import ai.core.server.quality.domain.AgentQualityRule;
import ai.core.server.quality.domain.AgentQualityRuleRevision;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityRuleKind;
import ai.core.server.quality.domain.QualityRuleLifecycle;
import core.framework.inject.Inject;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Rule governance. Every method re-checks that the caller is a human platform admin; route annotations
 * alone are not enough because API users bypass action permissions.
 *
 * @author core-ai
 */
public class AgentQualityAdminService {
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_.]{2,80}$");

    @Inject
    QualityPolicyService policyService;
    @Inject
    AgentQualityStore store;
    @Inject
    QualityRuleRegistry registry;
    @Inject
    PublishQualityGateService qualityGate;
    @Inject
    AgentDefinitionStore agentDefinitionStore;
    @Inject
    PermissionService permissionService;

    public AgentQualityRuleTemplateListResponse templates(String userId) {
        policyService.requireAdmin(userId);
        var response = new AgentQualityRuleTemplateListResponse();
        response.templates = registry.templates().stream().map(this::toTemplate).toList();
        response.inputOptions = QualityRuleRegistry.inputOptions();
        return response;
    }

    public AgentQualityRuleListResponse rules(String userId) {
        policyService.requireAdmin(userId);
        var policy = policyService.active();
        var enforcementByRule = new LinkedHashMap<String, QualityEnforcement>();
        for (var rule : policy.rules()) enforcementByRule.put(rule.revision().ruleId, rule.enforcement());

        var response = new AgentQualityRuleListResponse();
        response.policyRevision = policy.revision();
        response.rules = store.listRules().stream()
            .filter(rule -> rule.lifecycle != QualityRuleLifecycle.RETIRED)
            .map(rule -> toView(rule, enforcementByRule.get(rule.id)))
            .toList();
        return response;
    }

    public AgentQualityRuleView createRule(CreateAgentQualityRuleRequest request, String userId) {
        policyService.requireAdmin(userId);
        if (request.key == null || !KEY_PATTERN.matcher(request.key).matches()) {
            throw new BadRequestException("quality rule key must match " + KEY_PATTERN.pattern());
        }
        if (store.findRuleByKey(request.key).isPresent()) {
            throw new BadRequestException("quality rule key already exists: " + request.key);
        }
        validateCustomRule(request);
        var now = ZonedDateTime.now();
        var rule = new AgentQualityRule();
        rule.id = "qrule:" + UUID.randomUUID();
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

    public AgentQualityRuleView updateRule(String ruleId, UpdateAgentQualityRuleRequest request, String userId) {
        policyService.requireAdmin(userId);
        var rule = policyService.requireRule(ruleId);
        if (rule.lifecycle == QualityRuleLifecycle.RETIRED) {
            throw new BadRequestException("retired quality rule cannot be edited");
        }
        var latest = policyService.requireRevision(ruleId, rule.latestRevision);
        if (request.parameters != null) latest.parameters = new LinkedHashMap<>(request.parameters);
        if (request.decisionQuestion != null) latest.decisionQuestion = request.decisionQuestion;
        if (request.decisionInstruction != null) latest.decisionInstruction = request.decisionInstruction;
        if (request.decisionCriteria != null) latest.decisionCriteria = new LinkedHashMap<>(request.decisionCriteria);
        if (request.decisionLevels != null) latest.decisionLevels = List.copyOf(request.decisionLevels);
        if (request.decisionType != null) latest.decisionType = request.decisionType;
        if (request.affirmativeThreshold != null) latest.affirmativeThreshold = request.affirmativeThreshold;
        if (request.negativeThreshold != null) latest.negativeThreshold = request.negativeThreshold;
        if (request.modelAlias != null) latest.modelAlias = request.modelAlias;
        if (request.expectedRouteFingerprint != null) latest.expectedRouteFingerprint = request.expectedRouteFingerprint;
        if (request.messageTemplate != null) latest.messageTemplate = request.messageTemplate;
        if (request.remediationTemplate != null) latest.remediationTemplate = request.remediationTemplate;
        if (request.inputSelector != null) latest.inputSelector = List.copyOf(request.inputSelector);
        if (request.name != null) latest.name = request.name;
        if (request.description != null) latest.description = request.description;
        if (request.rationale != null) latest.rationale = request.rationale;
        validateThresholds(latest.affirmativeThreshold, latest.negativeThreshold);

        int nextRevision = rule.latestRevision + 1;
        latest.id = rule.id + ":" + nextRevision;
        latest.revision = nextRevision;
        latest.changeReason = request.changeReason;
        latest.createdBy = userId;
        latest.createdAt = ZonedDateTime.now();
        latest.tested = Boolean.FALSE;
        latest.testedAt = null;
        latest.testSummary = null;
        store.insertRevision(latest);

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

    public TestAgentQualityRuleResponse testRule(String ruleId, TestAgentQualityRuleRequest request, String userId) {
        policyService.requireAdmin(userId);
        var rule = policyService.requireRule(ruleId);
        var revision = policyService.requireRevision(ruleId, rule.latestRevision);
        var agent = agentDefinitionStore.get(request.agentId)
            .orElseThrow(() -> new NotFoundException("agent not found, id=" + request.agentId));
        if (!permissionService.checkResource(userId, PermissionService.RESOURCE_TYPE_AGENT, agent.id)) {
            throw new ForbiddenException("no permission to use this agent as a test fixture");
        }
        if (request.description != null) agent.description = request.description;
        if (request.systemPrompt != null && agent.type != ai.core.server.domain.DefinitionType.LLM_CALL) {
            agent.systemPrompt = request.systemPrompt;
            agent.systemPromptId = null;
        }
        var candidate = qualityGate.buildCandidate(agent);
        long started = System.currentTimeMillis();
        var evaluation = qualityGate.testRule(candidate, revision);
        var response = new TestAgentQualityRuleResponse();
        response.ruleId = ruleId;
        response.ruleRevision = revision.revision;
        response.result = evaluation.status().name();
        response.issues = QualityReportMapper.issueViews(evaluation.issues(), true);
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

    public AgentQualityRuleView retireRule(String ruleId, String userId) {
        policyService.requireAdmin(userId);
        policyService.retireRule(ruleId, userId);
        return toView(policyService.requireRule(ruleId), null);
    }

    public AgentQualityPolicyListResponse policies(String userId) {
        policyService.requireAdmin(userId);
        var active = policyService.active();
        var response = new AgentQualityPolicyListResponse();
        response.active = toPolicyView(active.policy(), active.generation(), true);
        response.history = policyService.history().stream()
            .map(policy -> toPolicyView(policy, null, policy.revision == active.revision()))
            .toList();
        return response;
    }

    public AgentQualityPolicyView activate(ActivateAgentQualityPolicyRequest request, String userId) {
        policyService.requireAdmin(userId);
        if (request.changeReason == null || request.changeReason.isBlank()) {
            throw new BadRequestException("change_reason is required");
        }
        QualityEnforcement enforcement = null;
        if (request.enforcement != null) {
            try {
                enforcement = QualityEnforcement.valueOf(request.enforcement);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("invalid enforcement: " + request.enforcement, "BAD_REQUEST", e);
            }
            if (enforcement != QualityEnforcement.SHADOW && request.ruleRevision != null) {
                var rule = policyService.requireRule(request.ruleId);
                var revision = policyService.requireRevision(request.ruleId, request.ruleRevision);
                requireTested(rule, revision);
            }
        }
        var command = new QualityPolicyService.ActivatePolicyCommand(request.policyRevision,
            request.ruleId, request.ruleRevision, enforcement, request.expectedGeneration,
            request.changeReason, request.suspend, request.resume);
        var policy = policyService.createPolicy(command, userId);
        var head = policyService.requireHead();
        return toPolicyView(policy, head.generation, true);
    }

    public void requireTested(AgentQualityRule rule, AgentQualityRuleRevision revision) {
        if (rule.lastTestedRevision == null || !rule.lastTestedRevision.equals(revision.revision)) {
            throw new BadRequestException("rule revision must be tested before it can block publishes");
        }
    }

    private AgentQualityRuleRevision revision(AgentQualityRule rule, int revision,
                                              CreateAgentQualityRuleRequest request, String userId,
                                              String changeReason) {
        var entity = new AgentQualityRuleRevision();
        entity.id = rule.id + ":" + revision;
        entity.ruleId = rule.id;
        entity.revision = revision;
        entity.name = request.name;
        entity.category = rule.category;
        entity.description = request.description;
        entity.rationale = request.rationale;
        entity.kind = QualityRuleKind.DECISION;
        entity.evaluatorKey = DecisionQualityRule.EVALUATOR_KEY;
        entity.evaluatorVersion = 1;
        entity.parameters = request.parameters == null ? Map.of() : new LinkedHashMap<>(request.parameters);
        entity.inputSelector = request.inputSelector == null ? List.of(CustomRuleInput.SYSTEM_PROMPT)
            : List.copyOf(request.inputSelector);
        entity.decisionType = request.decisionType == null ? "noul" : request.decisionType;
        entity.decisionQuestion = request.decisionQuestion;
        entity.decisionInstruction = request.decisionInstruction;
        entity.decisionCriteria = request.decisionCriteria == null ? null : new LinkedHashMap<>(request.decisionCriteria);
        entity.decisionLevels = request.decisionLevels == null ? null : List.copyOf(request.decisionLevels);
        entity.decisionPolarity = request.decisionPolarity == null
            ? DecisionSupport.AFFIRMATIVE_MEANS_COMPLIANT : request.decisionPolarity;
        entity.affirmativeThreshold = request.affirmativeThreshold == null ? 0.80 : request.affirmativeThreshold;
        entity.negativeThreshold = request.negativeThreshold == null ? 0.20 : request.negativeThreshold;
        entity.modelAlias = request.modelAlias;
        entity.expectedRouteFingerprint = request.expectedRouteFingerprint;
        entity.messageTemplate = request.messageTemplate;
        entity.remediationTemplate = request.remediationTemplate;
        entity.tested = Boolean.FALSE;
        entity.changeReason = changeReason;
        entity.createdBy = userId;
        entity.createdAt = ZonedDateTime.now();
        validateThresholds(entity.affirmativeThreshold, entity.negativeThreshold);
        return entity;
    }

    private void validateCustomRule(CreateAgentQualityRuleRequest request) {
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
        validateThresholds(request.affirmativeThreshold == null ? 0.80 : request.affirmativeThreshold,
            request.negativeThreshold == null ? 0.20 : request.negativeThreshold);
        if (request.inputSelector != null) {
            for (var input : request.inputSelector) {
                if (!QualityRuleRegistry.inputOptions().contains(input)) {
                    throw new BadRequestException("unsupported rule input: " + input);
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

    private AgentQualityRuleView toView(AgentQualityRule rule, QualityEnforcement enforcement) {
        var revision = store.getRevision(rule.id, rule.latestRevision).orElse(null);
        var view = new AgentQualityRuleView();
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
        view.editableParameters = parametersOf(revision.evaluatorKey);
        return view;
    }

    private List<String> parametersOf(String evaluatorKey) {
        try {
            return registry.require(evaluatorKey).template().parameters().stream()
                .map(QualityRuleEvaluator.RuleParameterSpec::name).toList();
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    private AgentQualityPolicyView toPolicyView(AgentQualityPolicyRevision policy, Integer generation, boolean active) {
        var view = new AgentQualityPolicyView();
        view.revision = policy.revision;
        view.generation = generation;
        view.scope = policy.scope;
        view.active = active;
        view.changeReason = policy.changeReason;
        view.activatedBy = policy.activatedBy;
        view.activatedAt = policy.activatedAt;
        var bindings = new ArrayList<AgentQualityPolicyBindingView>();
        if (policy.bindings != null) {
            for (var binding : policy.bindings) {
                var item = new AgentQualityPolicyBindingView();
                item.ruleId = binding.ruleId;
                item.ruleRevision = binding.ruleRevision;
                item.enforcement = binding.enforcement == null ? null : binding.enforcement.name();
                bindings.add(item);
            }
        }
        view.bindings = bindings;
        return view;
    }

    private AgentQualityRuleTemplateView toTemplate(QualityRuleEvaluator.RuleTemplate template) {
        var view = new AgentQualityRuleTemplateView();
        view.evaluatorKey = template.evaluatorKey();
        view.name = template.name();
        view.kind = template.kind();
        view.category = template.category();
        view.description = template.description();
        view.supportsDecision = template.supportsDecision();
        view.inputOptions = template.inputOptions();
        var parameters = new ArrayList<AgentQualityRuleParameterView>();
        for (var parameter : template.parameters()) {
            var item = new AgentQualityRuleParameterView();
            item.name = parameter.name();
            item.type = parameter.type();
            item.required = parameter.required();
            item.description = parameter.description();
            item.defaultValue = parameter.defaultValue();
            item.options = parameter.options();
            parameters.add(item);
        }
        view.parameters = parameters;
        return view;
    }
}










