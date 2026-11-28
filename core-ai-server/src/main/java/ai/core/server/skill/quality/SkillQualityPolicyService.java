package ai.core.server.skill.quality;

import ai.core.server.domain.SkillTier;
import ai.core.server.quality.QualityBudget;
import ai.core.server.quality.QualityExemptions;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityRuleBinding;
import ai.core.server.quality.domain.QualityRuleKind;
import ai.core.server.quality.domain.QualityRuleLifecycle;
import ai.core.server.skill.SkillTiers;
import ai.core.server.skill.quality.domain.SkillQualityPolicyHead;
import ai.core.server.skill.quality.domain.SkillQualityPolicyRevision;
import ai.core.server.skill.quality.domain.SkillQualityRule;
import ai.core.server.skill.quality.domain.SkillQualityRuleRevision;
import ai.core.server.user.UserStore;
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
import java.util.Optional;

/**
 * Versioned skill policy across the four independent tier scopes (skill:tier:imported, domain,
 * core, always-on). Each scope owns its own head and CAS generation counter. Legacy skill:server
 * rows are never read at runtime.
 *
 * @author core-ai
 */
public class SkillQualityPolicyService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillQualityPolicyService.class);
    public static final String SCOPE_SKILL_SERVER_LEGACY = "skill:server";
    public static final int MAX_DECISION_RULES = QualityBudget.MAX_PROVIDER_CALLS;
    public static final int MAX_POLICY_QUESTIONS = QualityBudget.MAX_QUESTIONS;
    private static final int SEED_INSERT_RETRIES = 5;

    public static String ruleId(String key) {
        return "sqrule:" + key;
    }

    @Inject
    SkillQualityStore store;
    @Inject
    SkillQualityRuleRegistry registry;
    @Inject
    UserStore userStore;

    public void seed() {
        var now = ZonedDateTime.now();
        for (var rule : SkillQualityGate.builtInRules()) {
            seedRule(rule.meta(), now);
        }
        for (var tier : SkillTier.values()) {
            seedTierPolicy(tier, now);
            seedTierHead(tier, now);
        }
        LOGGER.info("skill quality policies seeded, rules={}, scopes={}",
            SkillQualityGate.builtInRules().size(), SkillTier.values().length);
    }

    private void seedRule(SkillRuleMeta meta, ZonedDateTime now) {
        var id = ruleId(meta.key());
        var rule = new SkillQualityRule();
        rule.id = id;
        rule.key = meta.key();
        rule.name = meta.name();
        rule.category = meta.category();
        rule.builtIn = Boolean.TRUE;
        rule.latestRevision = 1;
        rule.lifecycle = meta.enforcement() == QualityEnforcement.SHADOW
            ? QualityRuleLifecycle.SHADOW : QualityRuleLifecycle.ENFORCING;
        rule.createdBy = "system";
        rule.createdAt = now;
        rule.updatedBy = "system";
        rule.updatedAt = now;
        store.insertRuleIfAbsent(rule);

        var revision = new SkillQualityRuleRevision();
        revision.id = id + ":1";
        revision.ruleId = id;
        revision.ruleKey = meta.key();
        revision.revision = 1;
        revision.name = meta.name();
        revision.category = meta.category();
        revision.description = meta.requirement();
        revision.rationale = "Built-in skill rule defined in code.";
        revision.kind = QualityRuleKind.CODE;
        revision.evaluatorKey = meta.key();
        revision.evaluatorVersion = meta.revision();
        revision.parameters = Map.of();
        revision.inputSelector = List.of();
        revision.affirmativeThreshold = 0.80;
        revision.negativeThreshold = 0.20;
        revision.tested = Boolean.TRUE;
        revision.testSummary = "Built-in rule verified with the release.";
        revision.changeReason = "built-in seed";
        revision.createdBy = "system";
        revision.createdAt = now;
        store.insertRevisionIfAbsent(revision);
    }

    private void seedTierPolicy(SkillTier tier, ZonedDateTime now) {
        var scope = tier.scope();
        if (!store.listPolicies(scope).isEmpty()) return;
        var bindings = new ArrayList<QualityRuleBinding>();
        for (var rule : SkillQualityGate.builtInRules()) {
            var binding = new QualityRuleBinding();
            binding.ruleId = ruleId(rule.meta().key());
            binding.ruleRevision = 1;
            binding.enforcement = SkillTierPolicyMatrix.enforcement(tier, rule.meta().key());
            bindings.add(binding);
        }
        for (int attempt = 0; attempt < SEED_INSERT_RETRIES; attempt++) {
            if (!store.listPolicies(scope).isEmpty()) return;
            int nextRevision = nextGlobalRevision();
            var policy = new SkillQualityPolicyRevision();
            policy.id = "skill-policy:" + nextRevision;
            policy.revision = nextRevision;
            policy.scope = scope;
            policy.bindings = bindings;
            policy.exemptions = List.of();
            policy.changeReason = "initial built-in skill policy for " + scope;
            policy.activatedBy = "system";
            policy.activatedAt = now;
            policy.generation = 1;
            if (store.insertPolicyIfAbsent(policy)) return;
        }
    }

    private void seedTierHead(SkillTier tier, ZonedDateTime now) {
        var scope = tier.scope();
        if (store.getHead(scope).isPresent()) return;
        var scopePolicies = store.listPolicies(scope);
        if (scopePolicies.isEmpty()) return;
        var activeRevision = scopePolicies.stream().mapToInt(policy -> policy.revision).max().orElse(1);
        var head = new SkillQualityPolicyHead();
        head.id = scope;
        head.scope = scope;
        head.activeRevision = activeRevision;
        head.generation = 1;
        head.updatedBy = "system";
        head.updatedAt = now;
        store.insertHeadIfAbsent(head);
    }

    public Optional<ActivePolicy> findActive(String scope) {
        var normalized = SkillTiers.requireScope(scope);
        var head = store.getHead(normalized).orElse(null);
        if (head == null) return Optional.empty();
        var policy = store.getPolicy(head.activeRevision).orElse(null);
        if (policy == null) {
            LOGGER.error("active skill policy revision is missing for scope={}, revision={}",
                normalized, head.activeRevision);
            return Optional.empty();
        }
        return Optional.of(toActivePolicy(policy, head));
    }

    public ActivePolicy active(String scope) {
        var normalized = SkillTiers.requireScope(scope);
        return findActive(normalized)
            .orElseThrow(() -> new IllegalStateException("skill quality policy head is not initialized for scope=" + normalized));
    }

    public SkillQualityPolicyHead requireHead(String scope) {
        var normalized = SkillTiers.requireScope(scope);
        return store.getHead(normalized)
            .orElseThrow(() -> new IllegalStateException("skill quality policy head is not initialized for scope=" + normalized));
    }

    public SkillQualityPolicyRevision createPolicy(String scope, ActivateCommand command, String userId) {
        var normalized = SkillTiers.requireScope(scope);
        var head = requireHead(normalized);
        if (command.expectedGeneration() != null && !command.expectedGeneration().equals(head.generation)) {
            throw new ConflictException("skill quality policy was changed by another administrator; refresh and retry");
        }
        var basePolicy = policyOf(command.rollbackRevision() != null ? command.rollbackRevision() : head.activeRevision);
        if (!normalized.equals(basePolicy.scope)) {
            throw new BadRequestException("policy revision " + basePolicy.revision + " does not belong to scope " + normalized);
        }
        var bindings = copyBindings(basePolicy);
        var exemptions = command.exemptions() == null
            ? QualityExemptions.copyOf(basePolicy.exemptions)
            : QualityExemptions.normalize(command.exemptions());
        applyCommand(bindings, command);
        validateBudget(bindings);
        return activatePolicy(normalized, head, bindings, exemptions, command.changeReason(), userId);
    }

    private void applyCommand(List<QualityRuleBinding> bindings, ActivateCommand command) {
        if (command.ruleId() != null) {
            var rule = requireRule(command.ruleId());
            if (rule.lifecycle == QualityRuleLifecycle.RETIRED) {
                throw new BadRequestException("retired skill quality rule cannot be activated: " + rule.key);
            }
            var revision = requireRevision(rule.id, command.ruleRevision() == null
                ? rule.latestRevision : command.ruleRevision());
            registry.require(revision.evaluatorKey);
            replaceBinding(bindings, rule.id, revision.revision, command.enforcement());
        }
        if (command.suspend() != null) {
            for (var ruleId : command.suspend()) {
                requireBinding(bindings, ruleId).enforcement = QualityEnforcement.SHADOW;
            }
        }
        if (command.resume() != null) {
            for (var ruleId : command.resume()) {
                requireBinding(bindings, ruleId).enforcement = command.enforcement() == null
                    ? QualityEnforcement.BLOCK : command.enforcement();
            }
        }
    }

    private SkillQualityPolicyRevision activatePolicy(String scope, SkillQualityPolicyHead head,
                                                      List<QualityRuleBinding> bindings,
                                                      List<String> exemptions, String changeReason, String userId) {
        int nextRevision = nextGlobalRevision();
        var policy = new SkillQualityPolicyRevision();
        policy.id = "skill-policy:" + nextRevision;
        policy.revision = nextRevision;
        policy.scope = scope;
        policy.bindings = bindings;
        policy.exemptions = exemptions;
        policy.changeReason = changeReason;
        policy.activatedBy = userId;
        policy.activatedAt = ZonedDateTime.now();
        policy.generation = head.generation + 1;
        store.insertPolicy(policy);

        var newHead = new SkillQualityPolicyHead();
        newHead.id = scope;
        newHead.scope = scope;
        newHead.activeRevision = nextRevision;
        newHead.generation = head.generation + 1;
        newHead.updatedBy = userId;
        newHead.updatedAt = ZonedDateTime.now();
        if (!store.replaceHead(newHead, head.generation)) {
            throw new ConflictException("skill quality policy was changed by another administrator; refresh and retry");
        }
        LOGGER.info("skill quality policy activated, scope={}, revision={}, generation={}, by={}",
            scope, nextRevision, newHead.generation, userId);
        return policy;
    }

    public List<SkillQualityPolicyRevision> history(String scope) {
        var normalized = SkillTiers.requireScope(scope);
        return store.listPolicies(normalized);
    }

    public List<SkillQualityPolicyRevision> historyAllTierScopes() {
        return store.listPolicies().stream()
            .filter(policy -> SkillTiers.tierForScope(policy.scope) != null)
            .toList();
    }

    public void retireRule(String ruleId, String userId) {
        for (var scope : SkillTiers.ALL_SCOPES) {
            retireRuleInScope(scope, ruleId, userId);
        }
        var rule = requireRule(ruleId);
        rule.lifecycle = QualityRuleLifecycle.RETIRED;
        rule.retiredAt = ZonedDateTime.now();
        rule.updatedAt = rule.retiredAt;
        rule.updatedBy = userId;
        store.replaceRule(rule);
    }

    public void retireRule(String scope, String ruleId, String userId) {
        var normalized = SkillTiers.requireScope(scope);
        retireRuleInScope(normalized, ruleId, userId);
    }

    private void retireRuleInScope(String scope, String ruleId, String userId) {
        var head = store.getHead(scope).orElse(null);
        if (head == null) return;
        var active = store.getPolicy(head.activeRevision).orElse(null);
        if (active == null) return;
        var bindings = copyBindings(active);
        if (bindings.removeIf(binding -> ruleId.equals(binding.ruleId))) {
            activatePolicy(scope, head, bindings, QualityExemptions.copyOf(active.exemptions),
                "retire skill rule " + ruleId, userId);
        }
    }

    private ActivePolicy toActivePolicy(SkillQualityPolicyRevision policy, SkillQualityPolicyHead head) {
        var rules = new ArrayList<ActiveRule>();
        for (var binding : policy.bindings == null ? List.<QualityRuleBinding>of() : policy.bindings) {
            var revision = store.getRevision(binding.ruleId, binding.ruleRevision).orElse(null);
            if (revision == null) {
                LOGGER.warn("active skill policy references a missing rule revision, scope={}, rule={}, revision={}",
                    head.scope, binding.ruleId, binding.ruleRevision);
                continue;
            }
            var enforcement = binding.enforcement == null ? QualityEnforcement.SHADOW : binding.enforcement;
            rules.add(new ActiveRule(revision, enforcement));
        }
        return new ActivePolicy(policy, head, rules);
    }

    private int nextGlobalRevision() {
        return store.listPolicies().stream().mapToInt(policy -> policy.revision).max().orElse(0) + 1;
    }

    private SkillQualityPolicyRevision policyOf(int revision) {
        return store.getPolicy(revision)
            .orElseThrow(() -> new BadRequestException("unknown skill quality policy revision: " + revision));
    }

    private List<QualityRuleBinding> copyBindings(SkillQualityPolicyRevision policy) {
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
        if (binding == null) throw new BadRequestException("rule is not part of the active skill policy: " + ruleId);
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
            if (SkillDecisionQualityRule.EVALUATOR_KEY.equals(revision.evaluatorKey)) {
                decisionRules++;
                questions++;
            }
        }
        if (decisionRules > MAX_DECISION_RULES || questions > MAX_POLICY_QUESTIONS) {
            throw new BadRequestException("skill quality policy exceeds the enforceable decision budget: rules="
                + decisionRules + ", questions=" + questions);
        }
    }

    public SkillQualityRule requireRule(String ruleId) {
        return store.getRule(ruleId)
            .orElseThrow(() -> new BadRequestException("unknown skill quality rule: " + ruleId));
    }

    public SkillQualityRuleRevision requireRevision(String ruleId, int revision) {
        return store.getRevision(ruleId, revision)
            .orElseThrow(() -> new BadRequestException("unknown skill quality rule revision: " + ruleId + "@" + revision));
    }

    public void requireAdmin(String userId) {
        var user = userStore.get(userId).orElse(null);
        if (user == null || !"admin".equals(user.role) || "api".equals(user.userType)) {
            throw new ForbiddenException("only a human platform admin can manage skill quality rules");
        }
    }

    public Map<String, QualityEnforcement> enforcements(String scope) {
        var result = new LinkedHashMap<String, QualityEnforcement>();
        try {
            for (var rule : active(scope).rules()) {
                result.put(rule.revision().ruleId, rule.enforcement());
            }
        } catch (RuntimeException e) {
            LOGGER.warn("skill quality policy is not available for scope={}: {}", scope, e.getMessage());
        }
        return result;
    }

    public record ActiveRule(SkillQualityRuleRevision revision, QualityEnforcement enforcement) {
    }

    public record ActivePolicy(SkillQualityPolicyRevision policy, SkillQualityPolicyHead head,
                               List<ActiveRule> rules) {
        public int revision() {
            return policy.revision;
        }

        public int generation() {
            return head.generation;
        }

        public String scope() {
            return head.scope;
        }

        public List<String> exemptions() {
            return policy.exemptions == null ? List.of() : List.copyOf(policy.exemptions);
        }
    }

    public record ActivateCommand(Integer rollbackRevision, String ruleId, Integer ruleRevision,
                                  QualityEnforcement enforcement, Integer expectedGeneration,
                                  String changeReason, List<String> suspend, List<String> resume,
                                  List<String> exemptions) {
    }
}
