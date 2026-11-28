package ai.core.server.skill.quality;

import ai.core.document.Tokenizer;
import ai.core.server.domain.SkillSourceType;
import ai.core.server.domain.SkillTier;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.QualityRuleBinding;
import ai.core.server.skill.quality.domain.SkillQualityPolicyHead;
import ai.core.server.skill.quality.domain.SkillQualityPolicyRevision;
import ai.core.server.user.UserStore;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SuppressFBWarnings("MRC_METHOD_RETURNS_CONSTANT")
class SkillTierGovernanceTest {
    private InMemorySkillQualityStore store;
    private SkillQualityRuleRegistry registry;
    private SkillQualityPolicyService policyService;
    private SkillQualityGate gate;

    @BeforeEach
    void setUp() {
        store = new InMemorySkillQualityStore();
        registry = new SkillQualityRuleRegistry();
        for (var rule : SkillQualityGate.builtInRules()) {
            registry.register(new SkillCodeRuleEvaluator(rule));
        }
        registry.register(new SkillDecisionQualityRule());

        policyService = new SkillQualityPolicyService();
        policyService.store = store;
        policyService.registry = registry;
        policyService.userStore = mock(UserStore.class);
        policyService.seed();

        gate = new SkillQualityGate();
        gate.policyService = policyService;
        gate.registry = registry;
    }

    @Test
    void defaultMatrixMatchesDesignSpecForAll12RulesAcross4Tiers() {
        assertThat(SkillQualityGate.builtInRules()).hasSize(12);
        assertRow("SKILL.PACKAGE_HYGIENE", QualityEnforcement.BLOCK, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK);
        assertRow("SKILL.NAME_IDENTITY", QualityEnforcement.BLOCK, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK);
        assertRow("SKILL.LINK_REACHABILITY", QualityEnforcement.WARN, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK);
        assertRow("SKILL.PERMISSION_DECLARATION", QualityEnforcement.WARN, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK);
        assertRow("SKILL.PERMISSION_UNUSED", QualityEnforcement.SHADOW, QualityEnforcement.WARN, QualityEnforcement.WARN, QualityEnforcement.WARN);
        assertRow("SKILL.METADATA_REQUIRED", QualityEnforcement.WARN, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK);
        assertRow("SKILL.VERSION_BUMP_REPO", QualityEnforcement.WARN, QualityEnforcement.SHADOW, QualityEnforcement.SHADOW, QualityEnforcement.SHADOW);
        assertRow("SKILL.DESCRIPTION_TRIGGER_SCOPE", QualityEnforcement.WARN, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK);
        assertRow("SKILL.DESCRIPTION_BUDGET", QualityEnforcement.SHADOW, QualityEnforcement.WARN, QualityEnforcement.WARN, QualityEnforcement.WARN);
        assertRow("SKILL.PROGRESSIVE_DISCLOSURE", QualityEnforcement.SHADOW, QualityEnforcement.WARN, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK);
        assertRow("SKILL.UNREFERENCED_REFERENCE", QualityEnforcement.SHADOW, QualityEnforcement.WARN, QualityEnforcement.WARN, QualityEnforcement.WARN);
        assertRow("SKILL.EVAL_DATASET", QualityEnforcement.SHADOW, QualityEnforcement.WARN, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK);
    }

    @Test
    void seedCreatesFourIsolatedHeadsAndIsIdempotent() {
        assertThat(store.listPolicies()).hasSize(4);
        for (var tier : SkillTier.values()) {
            var active = policyService.active(tier.scope());
            assertThat(active.scope()).isEqualTo(tier.scope());
            assertThat(active.generation()).isEqualTo(1);
            assertThat(active.rules()).hasSize(12);
        }

        policyService.seed();
        assertThat(store.listPolicies()).hasSize(4);
    }

    @Test
    void crossScopeCasCountersAreIndependent() {
        var evalRuleId = SkillQualityPolicyService.ruleId("SKILL.EVAL_DATASET");
        policyService.createPolicy(SkillTier.SCOPE_DOMAIN, new SkillQualityPolicyService.ActivateCommand(
            null, evalRuleId, 1, QualityEnforcement.BLOCK, 1, "tighten domain", null, null, null), "admin-1");

        assertThat(policyService.active(SkillTier.SCOPE_DOMAIN).generation()).isEqualTo(2);
        assertThat(policyService.active(SkillTier.SCOPE_CORE).generation()).isEqualTo(1);

        policyService.createPolicy(SkillTier.SCOPE_CORE, new SkillQualityPolicyService.ActivateCommand(
            null, evalRuleId, 1, QualityEnforcement.WARN, 1, "relax core", null, null, null), "admin-1");

        assertThat(policyService.active(SkillTier.SCOPE_CORE).generation()).isEqualTo(2);
        assertThat(policyService.enforcements(SkillTier.SCOPE_DOMAIN).get(evalRuleId)).isEqualTo(QualityEnforcement.BLOCK);
        assertThat(policyService.enforcements(SkillTier.SCOPE_CORE).get(evalRuleId)).isEqualTo(QualityEnforcement.WARN);

        assertThatThrownBy(() -> policyService.createPolicy(SkillTier.SCOPE_DOMAIN,
            new SkillQualityPolicyService.ActivateCommand(null, evalRuleId, 1, QualityEnforcement.WARN, 1,
                "stale generation", null, null, null), "admin-1"))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    void legacySkillServerHeadIsNeverReadAtRuntime() {
        var emptyStore = new InMemorySkillQualityStore();
        var legacyBinding = new QualityRuleBinding();
        legacyBinding.ruleId = SkillQualityPolicyService.ruleId("SKILL.EVAL_DATASET");
        legacyBinding.ruleRevision = 1;
        legacyBinding.enforcement = QualityEnforcement.BLOCK;
        var legacyPolicy = new SkillQualityPolicyRevision();
        legacyPolicy.id = "skill-policy:1";
        legacyPolicy.revision = 1;
        legacyPolicy.scope = SkillQualityPolicyService.SCOPE_SKILL_SERVER_LEGACY;
        legacyPolicy.bindings = List.of(legacyBinding);
        legacyPolicy.activatedAt = ZonedDateTime.now();
        emptyStore.insertPolicy(legacyPolicy);
        var legacyHead = new SkillQualityPolicyHead();
        legacyHead.id = SkillQualityPolicyService.SCOPE_SKILL_SERVER_LEGACY;
        legacyHead.scope = SkillQualityPolicyService.SCOPE_SKILL_SERVER_LEGACY;
        legacyHead.activeRevision = 1;
        legacyHead.generation = 1;
        emptyStore.insertHeadIfAbsent(legacyHead);

        var service = new SkillQualityPolicyService();
        service.store = emptyStore;
        service.registry = registry;
        service.userStore = mock(UserStore.class);

        assertThatThrownBy(() -> service.active("skill:server")).isInstanceOf(BadRequestException.class);

        var localGate = new SkillQualityGate();
        localGate.policyService = service;
        localGate.registry = registry;

        // DOMAIN head is missing, legacy skill:server has EVAL_DATASET=BLOCK.
        // Gate MUST fall back to compiled DOMAIN matrix (EVAL_DATASET=WARN), NOT skill:server.
        var candidate = new SkillPublishCandidateBuilder()
            .withTargetTier(SkillTier.DOMAIN)
            .build("demo", "demo-skill", minimalValidDomainMd(), Map.of(), SkillSourceType.UPLOAD, null);
        var result = localGate.evaluate(candidate);

        assertThat(result.gateResult()).isEqualTo(QualityGateResult.PASSED);
        assertThat(result.warnCount()).isPositive();
    }

    @Test
    void linkReachabilityBlocksBrokenLinkOnDomainWhileMissingSectionsOnlyWarn() {
        var brokenLinkMd = """
            ---
            name: demo-skill
            description: "Do demo. Use when asked. Scope: demo only."
            metadata:
              version: 1.0.0
              author: core-ai
              tags: demo
            ---
            See [Missing guide](references/missing.md).
            """;
        var candidate = new SkillPublishCandidateBuilder()
            .withTargetTier(SkillTier.DOMAIN)
            .build("demo", "demo-skill", brokenLinkMd, Map.of(), SkillSourceType.UPLOAD, null);

        var result = gate.evaluate(candidate);

        assertThat(result.gateResult()).isEqualTo(QualityGateResult.REJECTED);
        assertThat(result.issues()).anySatisfy(issue -> {
            assertThat(issue.ruleId).isEqualTo(SkillLinkReachabilityRule.KEY);
            assertThat(issue.enforcement).isEqualTo(QualityEnforcement.BLOCK);
            assertThat(issue.issueReason).isEqualTo("REFERENCE_NOT_PACKAGED");
        });
        assertThat(result.issues()).anySatisfy(issue -> {
            assertThat(issue.ruleId).isEqualTo("SKILL.PROGRESSIVE_DISCLOSURE");
            assertThat(issue.enforcement).isEqualTo(QualityEnforcement.WARN);
            assertThat(issue.issueReason).isEqualTo("MISSING_SECTION");
        });
    }

    @Test
    void repoNameIdentityAllowsDirectoryMismatchWithWarnButBlocksInvalidName() {
        var mismatchCandidate = new SkillPublishCandidateBuilder()
            .withTargetTier(SkillTier.IMPORTED)
            .build("vendor", "plugin-root-dir", """
            ---
            name: demo-skill
            description: Imported skill.
            ---
            # Demo
            """, Map.of(), SkillSourceType.REPO, null);
        var mismatchResult = gate.evaluate(mismatchCandidate);

        assertThat(mismatchResult.gateResult()).isEqualTo(QualityGateResult.PASSED);
        assertThat(mismatchResult.issues()).anySatisfy(issue -> {
            assertThat(issue.ruleId).isEqualTo("SKILL.NAME_IDENTITY");
            assertThat(issue.issueReason).isEqualTo("SKILL_DIRECTORY_MISMATCH_REPO");
            assertThat(issue.enforcement).isEqualTo(QualityEnforcement.WARN);
        });

        var invalidNameCandidate = new SkillPublishCandidateBuilder()
            .withTargetTier(SkillTier.IMPORTED)
            .build("vendor", "Bad_Name", """
            ---
            name: Bad_Name
            description: Imported skill.
            ---
            # Demo
            """, Map.of(), SkillSourceType.REPO, null);
        var invalidNameResult = gate.evaluate(invalidNameCandidate);

        assertThat(invalidNameResult.gateResult()).isEqualTo(QualityGateResult.REJECTED);
        assertThat(invalidNameResult.issues()).anySatisfy(issue -> {
            assertThat(issue.ruleId).isEqualTo("SKILL.NAME_IDENTITY");
            assertThat(issue.issueReason).isEqualTo("INVALID_SKILL_NAME");
            assertThat(issue.enforcement).isEqualTo(QualityEnforcement.BLOCK);
        });
    }

    @Test
    void repoPackageHygieneBlocksWithoutExemption() {
        var duplicateSkillMd = new SkillPublishCandidateBuilder()
            .withTargetTier(SkillTier.IMPORTED)
            .build("vendor", "demo-skill", """
            ---
            name: demo-skill
            description: Imported skill.
            ---
            # Demo
            """, Map.of("SKILL.md", "nested".getBytes(StandardCharsets.UTF_8)), SkillSourceType.REPO, null);

        var result = gate.evaluate(duplicateSkillMd);

        assertThat(result.gateResult()).isEqualTo(QualityGateResult.REJECTED);
        assertThat(result.exempted()).isFalse();
        assertThat(result.issues()).anySatisfy(issue -> {
            assertThat(issue.ruleId).isEqualTo("SKILL.PACKAGE_HYGIENE");
            assertThat(issue.enforcement).isEqualTo(QualityEnforcement.BLOCK);
        });
    }

    @Test
    void descriptionBudgetEvaluatesEnglishAndChineseTokensAndNeverBlocksInP1() {
        var rule = new SkillDescriptionBudgetRule();

        var shortEn = "Generate release notes. Use when asked for release notes. Scope: git repos only.";
        assertThat(Tokenizer.tokenCount(shortEn)).isLessThanOrEqualTo(120);
        assertThat(rule.evaluate(candidateWithDescription(shortEn, SkillTier.ALWAYS_ON)).issues()).isEmpty();

        var mediumZh = "用于根据产品需求文档和历史工单自动生成完整的测试用例矩阵与验收清单，覆盖边界条件、异常恢复、权限校验、并发冲突、性能基线和回滚验证。Use when 用户要求编写测试方案、验收清单或回归用例。Scope: 仅适用于研发交付与测试设计阶段，不用于线上生产环境变更审批或直接执行数据库订正操作。".repeat(2);
        int zhTokens = Tokenizer.tokenCount(mediumZh);
        assertThat(zhTokens).isGreaterThan(120);
        assertThat(zhTokens).isGreaterThan(250);

        var alwaysOnResult = rule.evaluate(candidateWithDescription(mediumZh, SkillTier.ALWAYS_ON));
        assertThat(alwaysOnResult.status()).isEqualTo(QualityIssueStatus.PASS);
        assertThat(alwaysOnResult.issues()).hasSize(1);
        assertThat(alwaysOnResult.issues().getFirst().issueReason).isEqualTo("ALWAYS_ON_BUDGET_EXCEEDED");

        var coreResult = rule.evaluate(candidateWithDescription(mediumZh, SkillTier.CORE));
        assertThat(coreResult.status()).isEqualTo(QualityIssueStatus.PASS);
        assertThat(coreResult.issues()).hasSize(1);
        assertThat(coreResult.issues().getFirst().issueReason).isEqualTo("CORE_BUDGET_EXCEEDED");

        var importedResult = rule.evaluate(candidateWithDescription(mediumZh, SkillTier.IMPORTED));
        assertThat(importedResult.issues()).isEmpty();

        var gateResult = gate.evaluate(candidateWithDescription(mediumZh, SkillTier.DOMAIN));
        assertThat(gateResult.gateResult()).isEqualTo(QualityGateResult.PASSED);
        assertThat(gateResult.issues()).anySatisfy(issue -> {
            assertThat(issue.ruleId).isEqualTo(SkillDescriptionBudgetRule.KEY);
            assertThat(issue.enforcement).isEqualTo(QualityEnforcement.WARN);
        });
    }

    private SkillPublishCandidate candidateWithDescription(String description, SkillTier tier) {
        var content = "---\nname: demo-skill\ndescription: \""
            + description.replace("\"", "\\\"")
            + "\"\nmetadata:\n  version: 1.0.0\n  author: core-ai\n  tags: demo\n---\n# Demo\n";
        return new SkillPublishCandidateBuilder()
            .withTargetTier(tier)
            .build("demo", "demo-skill", content, Map.of(),
                tier == SkillTier.IMPORTED ? SkillSourceType.REPO : SkillSourceType.UPLOAD, null);
    }

    private String minimalValidDomainMd() {
        return """
            ---
            name: demo-skill
            description: "Do demo things. Use when asked. Scope: demo only."
            metadata:
              version: 1.0.0
              author: core-ai
              tags: demo
            ---
            # Demo
            """;
    }

    private void assertRow(String ruleKey, QualityEnforcement imported, QualityEnforcement domain,
                           QualityEnforcement core, QualityEnforcement alwaysOn) {
        assertThat(SkillTierPolicyMatrix.enforcement(SkillTier.IMPORTED, ruleKey)).as(ruleKey + " IMPORTED").isEqualTo(imported);
        assertThat(SkillTierPolicyMatrix.enforcement(SkillTier.DOMAIN, ruleKey)).as(ruleKey + " DOMAIN").isEqualTo(domain);
        assertThat(SkillTierPolicyMatrix.enforcement(SkillTier.CORE, ruleKey)).as(ruleKey + " CORE").isEqualTo(core);
        assertThat(SkillTierPolicyMatrix.enforcement(SkillTier.ALWAYS_ON, ruleKey)).as(ruleKey + " ALWAYS_ON").isEqualTo(alwaysOn);
    }
}
