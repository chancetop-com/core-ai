package ai.core.server.quality;

import ai.core.server.quality.domain.AgentQualityPolicyHead;
import ai.core.server.quality.domain.AgentQualityPolicyRevision;
import ai.core.server.quality.domain.AgentQualityRule;
import ai.core.server.quality.domain.AgentQualityRuleRevision;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityRuleBinding;
import ai.core.server.quality.domain.QualityRuleLifecycle;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned publish policy. Seeding is idempotent so several replicas can start together; activation is a
 * single conditional head update, never a sequence of per-rule writes that could expose half a policy.
 *
 * @author core-ai
 */
public class QualityPolicyService {
    private static final Logger LOGGER = LoggerFactory.getLogger(QualityPolicyService.class);
    public static final String SCOPE_SERVER = "server";
    public static final int MAX_DECISION_RULES = QualityBudget.MAX_PROVIDER_CALLS;
    public static final int MAX_POLICY_QUESTIONS = QualityBudget.MAX_QUESTIONS;

    @Inject
    AgentQualityStore store;
    @Inject
    QualityRuleRegistry registry;
    @Inject
    ai.core.server.user.UserStore userStore;

    public void seed() {
        var now = ZonedDateTime.now();
        for (var definition : BuiltInQualityRules.DEFINITIONS) {
            seedRule(definition, now);
        }
        seedPolicy(now);
        seedHead(now);
        LOGGER.info("quality policy seeded, rules={}", BuiltInQualityRules.DEFINITIONS.size());
    }

    private void seedRule(BuiltInQualityRules.Definition definition, ZonedDateTime now) {
        var id = BuiltInQualityRules.ruleId(definition.key());
        var rule = new AgentQualityRule();
        rule.id = id;
        rule.key = definition.key();
        rule.name = definition.name();
        rule.category = definition.category();
        rule.builtIn = Boolean.TRUE;
        rule.latestRevision = 1;
        rule.lifecycle = BuiltInQualityRules.lifecycleOf(definition.enforcement());
        rule.createdBy = "system";
        rule.createdAt = now;
        rule.updatedBy = "system";
        rule.updatedAt = now;
        store.insertRuleIfAbsent(rule);

        var revision = new AgentQualityRuleRevision();
        revision.id = id + ":1";
        revision.ruleId = id;
        revision.revision = 1;
        revision.name = definition.name();
        revision.category = definition.category();
        revision.description = definition.description();
        revision.rationale = "内置规则，对应发布质量门禁的用户需求。";
        revision.kind = definition.kind();
        revision.evaluatorKey = definition.evaluatorKey();
        revision.evaluatorVersion = 1;
        revision.parameters = new LinkedHashMap<>(definition.parameters());
        revision.inputSelector = List.of();
        revision.affirmativeThreshold = 0.80;
        revision.negativeThreshold = 0.20;
        revision.messageTemplate = definition.messageTemplate();
        revision.remediationTemplate = definition.remediationTemplate();
        revision.tested = Boolean.FALSE;
        revision.testSummary = "内置规则，随版本发布验证。";
        revision.changeReason = "built-in seed";
        revision.createdBy = "system";
        revision.createdAt = now;
        store.insertRevisionIfAbsent(revision);
    }

    private void seedPolicy(ZonedDateTime now) {
        if (!store.listPolicies().isEmpty()) return;
        var bindings = new ArrayList<QualityRuleBinding>();
        for (var definition : BuiltInQualityRules.DEFINITIONS) {
            var binding = new QualityRuleBinding();
            binding.ruleId = BuiltInQualityRules.ruleId(definition.key());
            binding.ruleRevision = 1;
            binding.enforcement = definition.enforcement();
            bindings.add(binding);
        }
        var policy = new AgentQualityPolicyRevision();
        policy.id = "policy:1";
        policy.revision = 1;
        policy.scope = SCOPE_SERVER;
        policy.bindings = bindings;
        policy.changeReason = "initial built-in policy";
        policy.activatedBy = "system";
        policy.activatedAt = now;
        policy.generation = 1;
        store.insertPolicyIfAbsent(policy);
    }

    private void seedHead(ZonedDateTime now) {
        if (store.getHead(SCOPE_SERVER).isPresent()) return;
        var activeRevision = store.listPolicies().stream().mapToInt(policy -> policy.revision).max().orElse(1);
        var created = new AgentQualityPolicyHead();
        created.id = SCOPE_SERVER;
        created.scope = SCOPE_SERVER;
        created.activeRevision = activeRevision;
        created.generation = 1;
        created.updatedBy = "system";
        created.updatedAt = now;
        if (store.insertHeadIfAbsent(created)) {
            LOGGER.info("quality policy head seeded, revision={}", activeRevision);
        }
    }

    public ActivePolicy active() {
        var head = store.getHead(SCOPE_SERVER)
            .orElseThrow(() -> new IllegalStateException("quality policy head is not initialized"));
        var policy = store.getPolicy(head.activeRevision)
            .orElseThrow(() -> new IllegalStateException("active quality policy is missing, revision=" + head.activeRevision));
        var rules = new ArrayList<ActiveRule>();
        for (var binding : policy.bindings == null ? List.<QualityRuleBinding>of() : policy.bindings) {
            var revision = store.getRevision(binding.ruleId, binding.ruleRevision).orElse(null);
            if (revision == null) {
                LOGGER.warn("active quality policy references a missing rule revision, rule={}, revision={}",
                    binding.ruleId, binding.ruleRevision);
                continue;
            }
            var enforcement = binding.enforcement == null ? QualityEnforcement.SHADOW : binding.enforcement;
            rules.add(new ActiveRule(revision, enforcement));
        }
        return new ActivePolicy(policy, head, rules);
    }

    public AgentQualityPolicyHead requireHead() {
        return store.getHead(SCOPE_SERVER)
            .orElseThrow(() -> new IllegalStateException("quality policy head is not initialized"));
    }

    public AgentQualityPolicyRevision createPolicy(ActivatePolicyCommand command, String userId) {
        var head = requireHead();
        if (command.expectedGeneration() != null && !command.expectedGeneration().equals(head.generation)) {
            throw new ConflictException("quality policy was changed by another administrator; refresh and retry");
        }
        var baseBindings = command.rollbackRevision() != null
            ? bindingsOf(command.rollbackRevision())
            : bindingsOf(head.activeRevision);
        var bindings = new ArrayList<>(baseBindings);
        applyCommand(bindings, command);
        validateBudget(bindings);
        return activatePolicy(head, bindings, command.changeReason(), userId);
    }

    private void applyCommand(List<QualityRuleBinding> bindings, ActivatePolicyCommand command) {
        if (command.ruleId() != null) {
            var rule = requireRule(command.ruleId());
            if (rule.lifecycle == QualityRuleLifecycle.RETIRED) {
                throw new BadRequestException("retired quality rule cannot be activated: " + rule.key);
            }
            var revision = requireRevision(rule.id,
                command.ruleRevision() == null ? rule.latestRevision : command.ruleRevision());
            registry.require(revision.evaluatorKey);
            replaceBinding(bindings, rule.id, revision.revision, command.enforcement());
        }
        if (command.suspend() != null) {
            for (var ruleId : command.suspend()) {
                var binding = requireBinding(bindings, ruleId);
                binding.enforcement = QualityEnforcement.SHADOW;
            }
        }
        if (command.resume() != null) {
            for (var ruleId : command.resume()) {
                var binding = requireBinding(bindings, ruleId);
                binding.enforcement = command.enforcement() == null ? QualityEnforcement.BLOCK : command.enforcement();
            }
        }
    }

    private AgentQualityPolicyRevision activatePolicy(AgentQualityPolicyHead head, List<QualityRuleBinding> bindings,
                                                      String changeReason, String userId) {
        int nextRevision = store.listPolicies().stream().mapToInt(policy -> policy.revision).max().orElse(0) + 1;
        var policy = new AgentQualityPolicyRevision();
        policy.id = "policy:" + nextRevision;
        policy.revision = nextRevision;
        policy.scope = SCOPE_SERVER;
        policy.bindings = bindings;
        policy.changeReason = changeReason;
        policy.activatedBy = userId;
        policy.activatedAt = ZonedDateTime.now();
        policy.generation = head.generation + 1;
        store.insertPolicy(policy);

        var newHead = new AgentQualityPolicyHead();
        newHead.id = SCOPE_SERVER;
        newHead.scope = SCOPE_SERVER;
        newHead.activeRevision = nextRevision;
        newHead.generation = head.generation + 1;
        newHead.updatedBy = userId;
        newHead.updatedAt = ZonedDateTime.now();
        if (!store.replaceHead(newHead, head.generation)) {
            throw new ConflictException("quality policy was changed by another administrator; refresh and retry");
        }
        LOGGER.info("quality policy activated, revision={}, generation={}, by={}", nextRevision, newHead.generation, userId);
        return policy;
    }

    public List<AgentQualityPolicyRevision> history() {
        return store.listPolicies();
    }

    /** Removes a rule from the active policy and marks it retired in one audited step. */
    public void retireRule(String ruleId, String userId) {
        var head = requireHead();
        var bindings = bindingsOf(head.activeRevision);
        if (bindings.removeIf(binding -> ruleId.equals(binding.ruleId))) {
            activatePolicy(head, bindings, "retire rule " + ruleId, userId);
        }
        var rule = requireRule(ruleId);
        rule.lifecycle = QualityRuleLifecycle.RETIRED;
        rule.retiredAt = ZonedDateTime.now();
        rule.updatedAt = rule.retiredAt;
        rule.updatedBy = userId;
        store.replaceRule(rule);
    }

    private List<QualityRuleBinding> bindingsOf(int revision) {
        var policy = store.getPolicy(revision)
            .orElseThrow(() -> new BadRequestException("unknown quality policy revision: " + revision));
        var bindings = new ArrayList<QualityRuleBinding>();
        if (policy.bindings == null) return bindings;
        for (var binding : policy.bindings) {
            var copy = new QualityRuleBinding();
            copy.ruleId = binding.ruleId;
            copy.ruleRevision = binding.ruleRevision;
            copy.enforcement = binding.enforcement;
            bindings.add(copy);
        }
        return bindings;
    }

    private void replaceBinding(List<QualityRuleBinding> bindings, String ruleId, int revision,
                                QualityEnforcement enforcement) {
        var existing = findBinding(bindings, ruleId);
        if (existing == null) {
            var created = new QualityRuleBinding();
            created.ruleId = ruleId;
            created.ruleRevision = revision;
            created.enforcement = enforcement == null ? QualityEnforcement.SHADOW : enforcement;
            bindings.add(created);
            return;
        }
        existing.ruleRevision = revision;
        existing.enforcement = enforcement == null ? existing.enforcement : enforcement;
    }

    private QualityRuleBinding requireBinding(List<QualityRuleBinding> bindings, String ruleId) {
        var binding = findBinding(bindings, ruleId);
        if (binding == null) throw new BadRequestException("rule is not part of the active policy: " + ruleId);
        return binding;
    }

    private QualityRuleBinding findBinding(List<QualityRuleBinding> bindings, String ruleId) {
        return bindings.stream().filter(binding -> ruleId.equals(binding.ruleId)).findFirst().orElse(null);
    }

    private void validateBudget(List<QualityRuleBinding> bindings) {
        int decisionRules = 0;
        int questions = 0;
        for (var binding : bindings) {
            if (binding.enforcement == QualityEnforcement.SHADOW) continue;
            var revision = store.getRevision(binding.ruleId, binding.ruleRevision).orElse(null);
            if (revision == null) continue;
            var evaluator = registry.require(revision.evaluatorKey);
            var cost = estimatedQuestions(evaluator.evaluatorKey());
            if (cost > 0) {
                decisionRules++;
                questions += cost;
            }
        }
        if (decisionRules > MAX_DECISION_RULES || questions > MAX_POLICY_QUESTIONS) {
            throw new BadRequestException("quality policy exceeds the enforceable decision budget: rules="
                + decisionRules + ", questions=" + questions);
        }
    }

    private int estimatedQuestions(String evaluatorKey) {
        if (DecisionQualityRule.EVALUATOR_KEY.equals(evaluatorKey)
            || TimeContextRule.KEY.equals(evaluatorKey)
            || FunctionSummaryRule.KEY.equals(evaluatorKey)) {
            return 1;
        }
        if (BuiltinCapabilityRule.KEY.equals(evaluatorKey)) {
            return EffectiveCapabilityResolver.CAPABILITIES.size();
        }
        if (ResourceBindingRule.KEY.equals(evaluatorKey)) {
            return 13;
        }
        return 0;
    }

    public AgentQualityRule requireRule(String ruleId) {
        return store.getRule(ruleId).orElseThrow(() -> new BadRequestException("unknown quality rule: " + ruleId));
    }

    public AgentQualityRuleRevision requireRevision(String ruleId, int revision) {
        return store.getRevision(ruleId, revision)
            .orElseThrow(() -> new BadRequestException("unknown quality rule revision: " + ruleId + "@" + revision));
    }

    public void requireAdmin(String userId) {
        var user = userStore.get(userId).orElse(null);
        if (user == null || !"admin".equals(user.role) || "api".equals(user.userType)) {
            throw new ForbiddenException("only a human platform admin can manage publish quality rules");
        }
    }

    public Map<String, QualityEnforcement> enforcements() {
        var result = new LinkedHashMap<String, QualityEnforcement>();
        try {
            for (var rule : active().rules()) {
                result.put(rule.revision().ruleId, rule.enforcement());
            }
        } catch (RuntimeException e) {
            LOGGER.warn("quality policy is not available: {}", e.getMessage());
        }
        return result;
    }

    public record ActiveRule(AgentQualityRuleRevision revision, QualityEnforcement enforcement) {
    }

    public record ActivePolicy(AgentQualityPolicyRevision policy, AgentQualityPolicyHead head,
                               List<ActiveRule> rules) {
        public int revision() {
            return policy.revision;
        }

        public int generation() {
            return head.generation;
        }
    }

    public record ActivatePolicyCommand(Integer rollbackRevision, String ruleId, Integer ruleRevision,
                                        QualityEnforcement enforcement, Integer expectedGeneration,
                                        String changeReason, List<String> suspend, List<String> resume) {
    }
}
