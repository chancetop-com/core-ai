package ai.core.server.pg;

import ai.core.server.domain.SkillSourceType;
import ai.core.server.domain.SkillTier;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.skill.quality.PgSkillQualityStore;
import ai.core.server.skill.quality.SkillCodeRuleEvaluator;
import ai.core.server.skill.quality.SkillDecisionQualityRule;
import ai.core.server.skill.quality.SkillPublishCandidateBuilder;
import ai.core.server.skill.quality.SkillQualityGate;
import ai.core.server.skill.quality.SkillQualityPolicyService;
import ai.core.server.skill.quality.SkillQualityRuleRegistry;
import ai.core.server.user.UserStore;
import core.framework.internal.db.DatabaseImpl;
import core.framework.web.exception.ConflictException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Real PostgreSQL integration tests for multi-scope skill quality policies, independent per-scope
 * head CAS, listPolicies(scope), and tier-differentiated rule enforcement.
 *
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings({"REC_CATCH_EXCEPTION", "SACM_STATIC_ARRAY_CREATED_IN_METHOD", "RFI_SET_ACCESSIBLE", "MRC_METHOD_RETURNS_CONSTANT"})
class PgSkillQualityStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("skill-quality-store-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgDocumentRow.class);
        createTables();
        return database;
    }

    private static void createTables() {
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS app");
            for (var table : new String[]{
                "skill_quality_rules",
                "skill_quality_rule_revisions",
                "skill_quality_policy_revisions",
                "skill_quality_policy_heads"}) {
                statement.execute("CREATE TABLE IF NOT EXISTS app." + table
                    + " (id text PRIMARY KEY, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())");
            }
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS skill_quality_rules_key_idx "
                + "ON app.skill_quality_rules ((data ->> 'ruleKey'))");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS skill_quality_rule_revisions_rule_rev_idx "
                + "ON app.skill_quality_rule_revisions ((data ->> 'ruleId'), ((data ->> 'revision')::int))");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS skill_quality_policy_revisions_rev_idx "
                + "ON app.skill_quality_policy_revisions (((data ->> 'revision')::int))");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS skill_quality_policy_heads_scope_idx "
                + "ON app.skill_quality_policy_heads ((data ->> 'scope'))");
            statement.execute("CREATE INDEX IF NOT EXISTS skill_quality_policy_revisions_scope_rev_idx "
                + "ON app.skill_quality_policy_revisions ((data ->> 'scope'), ((data ->> 'revision')::int) DESC)");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize skill quality test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private static PgSkillQualityStore store() {
        var store = new PgSkillQualityStore();
        store.database = DATABASE;
        return store;
    }

    private static void cleanPolicyTables() {
        DATABASE.execute("DELETE FROM app.skill_quality_policy_heads");
        DATABASE.execute("DELETE FROM app.skill_quality_policy_revisions");
        DATABASE.execute("DELETE FROM app.skill_quality_rule_revisions");
        DATABASE.execute("DELETE FROM app.skill_quality_rules");
    }

    private static SkillQualityRuleRegistry registry() {
        var registry = new SkillQualityRuleRegistry();
        for (var rule : SkillQualityGate.builtInRules()) {
            registry.register(new SkillCodeRuleEvaluator(rule));
        }
        registry.register(new SkillDecisionQualityRule());
        return registry;
    }

    private static SkillQualityPolicyService policyService(PgSkillQualityStore store, SkillQualityRuleRegistry registry)
        throws ReflectiveOperationException {
        var service = new SkillQualityPolicyService();
        inject(service, "store", store);
        inject(service, "registry", registry);
        inject(service, "userStore", mock(UserStore.class));
        return service;
    }

    private static void inject(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void fourScopesSeedIndependentlyIsolateListPoliciesAndEnforcePerScopeCas() throws Exception {
        cleanPolicyTables();
        var store = store();
        var registry = registry();
        var policyService = policyService(store, registry);
        try {
            policyService.seed();
            policyService.seed();

            assertEquals(4, store.listPolicies().size());
            for (var tier : SkillTier.values()) {
                var head = store.getHead(tier.scope()).orElseThrow();
                assertEquals(tier.scope(), head.id);
                assertEquals(1, head.generation);
                var scopeRevisions = store.listPolicies(tier.scope());
                assertEquals(1, scopeRevisions.size());
                assertEquals(tier.scope(), scopeRevisions.getFirst().scope);
            }

            var evalRuleId = SkillQualityPolicyService.ruleId("SKILL.EVAL_DATASET");
            var domainRev = policyService.createPolicy(SkillTier.SCOPE_DOMAIN,
                new SkillQualityPolicyService.ActivateCommand(null, evalRuleId, 1, QualityEnforcement.BLOCK,
                    1, "tighten domain in pg test", null, null, null), "admin-1");
            assertEquals(2, domainRev.generation);
            assertEquals(2, store.listPolicies(SkillTier.SCOPE_DOMAIN).size());
            assertEquals(1, store.listPolicies(SkillTier.SCOPE_CORE).size());

            // CORE head generation is still 1 and must not be affected by DOMAIN update
            var coreRev = policyService.createPolicy(SkillTier.SCOPE_CORE,
                new SkillQualityPolicyService.ActivateCommand(null, evalRuleId, 1, QualityEnforcement.WARN,
                    1, "relax core in pg test", null, null, null), "admin-1");
            assertEquals(2, coreRev.generation);
            assertTrue(coreRev.revision > domainRev.revision);

            // Stale expectedGeneration on DOMAIN must fail CAS with ConflictException
            assertThrows(ConflictException.class, () -> policyService.createPolicy(SkillTier.SCOPE_DOMAIN,
                new SkillQualityPolicyService.ActivateCommand(null, evalRuleId, 1, QualityEnforcement.WARN,
                    1, "stale cas", null, null, null), "admin-1"));

            // Direct store replaceHead with wrong generation must return false
            var staleHead = store.getHead(SkillTier.SCOPE_DOMAIN).orElseThrow();
            assertFalse(store.replaceHead(staleHead, 999));
        } finally {
            cleanPolicyTables();
        }
    }

    @Test
    void multiScopeMatrixAppliesSameRuleDifferentlyAcrossDomainAndCoreInPg() throws Exception {
        cleanPolicyTables();
        var store = store();
        var registry = registry();
        var policyService = policyService(store, registry);
        try {
            policyService.seed();
            var gate = new SkillQualityGate();
            inject(gate, "policyService", policyService);
            inject(gate, "registry", registry);

            var skillMd = """
                ---
                name: demo-skill
                description: "Do demo work. Use when asked for demo. Scope: demo only."
                metadata:
                  version: 1.0.0
                  author: core-ai
                  tags: demo
                ---
                ## Workflow
                1. Execute.
                ## Decision logic
                Decide.
                ## Output specification
                Output.
                ## Error handling
                Retry.
                ## Quality assurance
                Verify.
                """;

            var domainCandidate = new SkillPublishCandidateBuilder()
                .withTargetTier(SkillTier.DOMAIN)
                .build("team", "demo-skill", skillMd, Map.of(), SkillSourceType.UPLOAD, null);
            var domainResult = gate.evaluate(domainCandidate);
            assertEquals(QualityGateResult.PASSED, domainResult.gateResult());
            assertTrue(domainResult.warnCount() > 0);
            assertTrue(domainResult.issues().stream().anyMatch(issue ->
                issue.enforcement == QualityEnforcement.WARN && "SKILL.EVAL_DATASET".equals(issue.ruleId)));

            var coreCandidate = new SkillPublishCandidateBuilder()
                .withTargetTier(SkillTier.CORE)
                .build("team", "demo-skill", skillMd, Map.of(), SkillSourceType.UPLOAD, null);
            var coreResult = gate.evaluate(coreCandidate);
            assertEquals(QualityGateResult.REJECTED, coreResult.gateResult());
            assertTrue(coreResult.issues().stream().anyMatch(issue ->
                issue.enforcement == QualityEnforcement.BLOCK && "SKILL.EVAL_DATASET".equals(issue.ruleId)));
        } finally {
            cleanPolicyTables();
        }
    }
}
