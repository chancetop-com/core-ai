package ai.core.server.skill;

import ai.core.api.server.skill.SkillTierView;
import ai.core.api.server.skill.UpdateSkillTierRequest;
import ai.core.server.domain.SkillTier;
import ai.core.server.domain.User;
import ai.core.server.pg.PgDocumentRow;
import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.skill.quality.PgSkillQualityStore;
import ai.core.server.skill.quality.SkillCodeRuleEvaluator;
import ai.core.server.skill.quality.SkillDecisionQualityRule;
import ai.core.server.skill.quality.SkillQualityGate;
import ai.core.server.skill.quality.SkillQualityGateException;
import ai.core.server.skill.quality.SkillQualityPolicyService;
import ai.core.server.skill.quality.SkillQualityRuleRegistry;
import ai.core.server.user.UserStore;
import core.framework.internal.db.DatabaseImpl;
import core.framework.web.exception.ConflictException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end PostgreSQL verification for all five P1 DoD functional scenarios.
 *
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings({"REC_CATCH_EXCEPTION", "SACM_STATIC_ARRAY_CREATED_IN_METHOD", "RFI_SET_ACCESSIBLE", "MRC_METHOD_RETURNS_CONSTANT"})
class SkillLifecycleGovernanceE2ETest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("skill-lifecycle-e2e-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgDocumentRow.class);
        database.view(PgSkillCountRow.class);
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
                "skills",
                "skill_quality_rules",
                "skill_quality_rule_revisions",
                "skill_quality_policy_revisions",
                "skill_quality_policy_heads"}) {
                statement.execute("CREATE TABLE IF NOT EXISTS app." + table
                    + " (id text PRIMARY KEY, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())");
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize E2E test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private static void inject(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void verifiesDomainUploadCorePromotionAlwaysOnQuotaAndMount() throws Exception {
        var env = buildEnv();
        var createdIds = new ArrayList<String>();
        var namespace = uniqueNamespace();
        try {
            var outcome = env.skillService.uploadOutcome("author-1", namespace, "domain-demo",
                skillContent("domain-demo").getBytes(StandardCharsets.UTF_8), null);
            var uploaded = outcome.entity();
            createdIds.add(uploaded.id);
            assertEquals(QualityGateResult.PASSED, outcome.report().gateResult);
            assertEquals(SkillTier.DOMAIN, uploaded.tier);
            assertTrue(outcome.report().warnCount > 0);

            var promoteCore = new UpdateSkillTierRequest();
            promoteCore.tier = SkillTierView.CORE;
            var rejected = assertThrows(SkillQualityGateException.class,
                () -> env.tierAdmin.updateTier(uploaded.id, promoteCore, "admin-1"));
            assertEquals(422, rejected.status().code);
            assertEquals(SkillQualityGateException.REJECTED, rejected.errorCode());
            assertTrue(rejected.extraJson().contains("SKILL.EVAL_DATASET"));

            var resolved = env.skillService.resolveAccessibleSkills(List.of(uploaded.id), "author-1");
            assertEquals(1, resolved.size());
            assertEquals(namespace + "/domain-demo", resolved.getFirst().getQualifiedName());
            var scopedSkills = env.provider.scoped(Set.of(uploaded.id)).listSkills();
            assertEquals(1, scopedSkills.size());
            assertEquals("domain-demo", scopedSkills.getFirst().getName());

            verifyAlwaysOnQuota(env, namespace, createdIds);
        } finally {
            for (var id : createdIds) env.skillStore.delete(id);
        }
    }

    @Test
    void verifiesMultiSkillRepoIsolationAndZeroRetryOnSameCommit() throws Exception {
        var env = buildEnv();
        var createdIds = new ArrayList<String>();
        var namespace = uniqueNamespace();
        var repoUrl = "https://github.com/" + namespace + "/multi-skill-repo";
        try {
            var manager = spy(new SkillRepoManager(env.skillStore, env.blobStore));
            doAnswer(invocation -> {
                Path target = invocation.getArgument(2);
                Path goodDir = target.resolve("skills/good-skill");
                Files.createDirectories(goodDir);
                Files.writeString(goodDir.resolve("SKILL.md"), "---\nname: good-skill\ndescription: Good repo skill.\n---\n# Good\n");
                Path badDir = target.resolve("skills/bad-skill");
                Files.createDirectories(badDir);
                Files.writeString(badDir.resolve("SKILL.md"), "---\nname: Bad_Invalid_Name\ndescription: Bad repo skill.\n---\n# Bad\n");
                return null;
            }).when(manager).cloneRepo(anyString(), any(), any(Path.class));
            doReturn("commit-sha-1").when(manager).headCommit(any(Path.class));

            var imported = repoServiceWith(env, manager).registerFromRepo("admin-1", repoUrl, "main", "skills");
            for (var s : imported) createdIds.add(s.id);
            assertEquals(1, imported.size());
            assertEquals("good-skill", imported.getFirst().name);
            assertEquals(SkillTier.IMPORTED, imported.getFirst().tier);
            assertFalse(env.skillStore.findByQualifiedName(namespace + "/Bad_Invalid_Name").isPresent());

            var existingGood = imported.getFirst();
            String oldDigest = existingGood.digest;
            var syncManager = spy(new SkillRepoManager(env.skillStore, env.blobStore));
            doReturn("commit-sha-bad").when(syncManager).remoteHead(repoUrl, "main");
            doAnswer(invocation -> {
                Path target = invocation.getArgument(2);
                Path goodDir = target.resolve("skills/good-skill");
                Files.createDirectories(goodDir);
                Files.writeString(goodDir.resolve("SKILL.md"), "---\nname: good-skill\ndescription: Broken update.\n---\n# Bad\n");
                return null;
            }).when(syncManager).cloneRepo(anyString(), any(), any(Path.class));
            doReturn(Map.of("SKILL.md", "duplicate".getBytes(StandardCharsets.UTF_8)))
                .when(syncManager).readResourceBytes(any(Path.class), any());

            assertThrows(SkillQualityGateException.class,
                () -> repoServiceWith(env, syncManager).syncFromRepoIfChanged(existingGood.id));
            var afterReject = env.skillStore.get(existingGood.id).orElseThrow();
            assertEquals(oldDigest, afterReject.digest);
            assertEquals(SkillRepoSyncHelper.STATUS_REJECTED, afterReject.repoConfig.lastSyncStatus);
            assertEquals("commit-sha-bad", afterReject.repoConfig.lastRejectedCommitHash);
            assertNotNull(afterReject.repoConfig.lastSyncError);

            var secondSyncManager = spy(new SkillRepoManager(env.skillStore, env.blobStore));
            doReturn("commit-sha-bad").when(secondSyncManager).remoteHead(repoUrl, "main");
            var skippedResult = repoServiceWith(env, secondSyncManager).syncFromRepoIfChanged(existingGood.id);
            assertEquals(oldDigest, skippedResult.digest);
            verify(secondSyncManager, never()).cloneRepo(anyString(), any(), any(Path.class));
        } finally {
            for (var id : createdIds) env.skillStore.delete(id);
        }
    }

    private void verifyAlwaysOnQuota(TestEnv env, String namespace, List<String> createdIds) {
        long existingAlwaysOn = env.skillStore.countByTier(SkillTier.ALWAYS_ON);
        int toFill = (int) Math.max(0, SkillTierAdminService.DEFAULT_MAX_ALWAYS_ON_SKILLS - existingAlwaysOn);
        for (int i = 0; i < toFill; i++) {
            var name = "pinned-" + i;
            var filled = env.skillService.upload("admin-1", namespace, name,
                skillContent(name).getBytes(StandardCharsets.UTF_8),
                Map.of("eval/eval.json", evalDataset(name).getBytes(StandardCharsets.UTF_8)));
            createdIds.add(filled.id);
            var toAlwaysOn = new UpdateSkillTierRequest();
            toAlwaysOn.tier = SkillTierView.ALWAYS_ON;
            assertEquals(SkillTierView.ALWAYS_ON, env.tierAdmin.updateTier(filled.id, toAlwaysOn, "admin-1").tier);
        }
        var eleventh = env.skillService.upload("admin-1", namespace, "pinned-overflow",
            skillContent("pinned-overflow").getBytes(StandardCharsets.UTF_8),
            Map.of("eval/eval.json", evalDataset("pinned-overflow").getBytes(StandardCharsets.UTF_8)));
        createdIds.add(eleventh.id);
        var overflowReq = new UpdateSkillTierRequest();
        overflowReq.tier = SkillTierView.ALWAYS_ON;
        var quotaError = assertThrows(ConflictException.class,
            () -> env.tierAdmin.updateTier(eleventh.id, overflowReq, "admin-1"));
        assertEquals(SkillTierAdminService.QUOTA_EXCEEDED_CODE, quotaError.errorCode());
    }

    private SkillService repoServiceWith(TestEnv env, SkillRepoManager manager) {
        var svc = new SkillService() {
            @Override
            SkillRepoManager repoManager() {
                return manager;
            }
        };
        svc.skillStore = env.skillStore;
        svc.blobStore = env.blobStore;
        svc.publishService = env.publishService;
        return svc;
    }

    private String uniqueNamespace() {
        return "e2e-" + Long.toUnsignedString(UUID.randomUUID().getLeastSignificantBits(), 36);
    }

    private TestEnv buildEnv() throws ReflectiveOperationException {
        var skillStore = new PgSkillStore();
        inject(skillStore, "database", DATABASE);
        var registry = buildRegistry();
        var policyService = buildPolicyService(registry);
        var gate = new SkillQualityGate();
        inject(gate, "policyService", policyService);
        inject(gate, "registry", registry);
        var blobStore = new SkillBlobStore();
        var publishService = new SkillPublishService();
        publishService.skillStore = skillStore;
        publishService.blobStore = blobStore;
        publishService.gate = gate;
        var skillService = new SkillService();
        skillService.skillStore = skillStore;
        skillService.blobStore = blobStore;
        skillService.publishService = publishService;
        var fixtureBuilder = new SkillQualityFixtureBuilder();
        fixtureBuilder.blobStore = blobStore;
        var tierAdmin = new SkillTierAdminService();
        tierAdmin.skillStore = skillStore;
        tierAdmin.policyService = policyService;
        tierAdmin.gate = gate;
        tierAdmin.fixtureBuilder = fixtureBuilder;
        tierAdmin.skillService = skillService;
        var provider = new MongoSkillProvider();
        provider.skillStore = skillStore;
        provider.skillService = skillService;
        return new TestEnv(skillStore, blobStore, publishService, skillService, tierAdmin, provider);
    }

    private SkillQualityRuleRegistry buildRegistry() {
        var registry = new SkillQualityRuleRegistry();
        for (var rule : SkillQualityGate.builtInRules()) {
            registry.register(new SkillCodeRuleEvaluator(rule));
        }
        registry.register(new SkillDecisionQualityRule());
        return registry;
    }

    private SkillQualityPolicyService buildPolicyService(SkillQualityRuleRegistry registry) throws ReflectiveOperationException {
        var qualityStore = new PgSkillQualityStore();
        qualityStore.database = DATABASE;
        var userStore = mock(UserStore.class);
        var admin = new User();
        admin.role = "admin";
        admin.userType = "internal";
        when(userStore.get("admin-1")).thenReturn(Optional.of(admin));
        var policyService = new SkillQualityPolicyService();
        inject(policyService, "store", qualityStore);
        inject(policyService, "registry", registry);
        inject(policyService, "userStore", userStore);
        policyService.seed();
        return policyService;
    }

    private String skillContent(String name) {
        return "---\nname: " + name + "\n"
            + "description: \"Do demo work. Use when asked for demo. Scope: demo only.\"\n"
            + "metadata:\n  version: 1.0.0\n  author: core-ai\n  tags: demo\n---\n"
            + "## Workflow\n1. Run.\n"
            + "## Decision logic\nDecide.\n"
            + "## Output specification\nOutput.\n"
            + "## Error handling\nRetry.\n"
            + "## Quality assurance\nVerify.\n";
    }

    private String evalDataset(String name) {
        var cases = List.of(
            evalCase("trigger-001", "trigger_accuracy", "{\"triggered\":true}"),
            evalCase("trigger-002", "trigger_accuracy", "{\"triggered\":false}"),
            evalCase("scope-001", "scope_accuracy", "{\"in_scope\":true,\"expected_behavior\":\"handle\"}"),
            evalCase("tool-001", "tool_accuracy", "{\"no_tools\":true}"),
            evalCase("param-001", "parameter_accuracy", "{\"tool\":\"read_file\",\"arguments\":{},\"match\":\"schema\"}"),
            evalCase("instr-001", "instruction_following", "{\"rules\":[\"one file\"]}"),
            evalCase("output-001", "output_quality", "{\"outputs\":[]}"),
            evalCase("robust-001", "robustness", "{\"failure_mode\":\"missing\",\"expected_behavior\":\"ask\"}")
        );
        return "{\"schema_version\":1,\"skill\":\"" + name + "\",\"dataset_version\":\"1.0.0\",\"cases\":["
            + String.join(",", cases) + "]}";
    }

    private String evalCase(String id, String dimension, String expected) {
        return "{\"id\":\"" + id + "\",\"dimension\":\"" + dimension + "\","
            + "\"input\":{\"user_request\":\"run demo\"},"
            + "\"expected\":" + expected + ",\"assertions\":[{\"kind\":\"no_crash\"}]}";
    }

    private record TestEnv(PgSkillStore skillStore, SkillBlobStore blobStore, SkillPublishService publishService,
                           SkillService skillService, SkillTierAdminService tierAdmin, MongoSkillProvider provider) {
    }
}
