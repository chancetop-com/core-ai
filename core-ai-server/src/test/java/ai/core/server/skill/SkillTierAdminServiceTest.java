package ai.core.server.skill;

import ai.core.api.server.skill.SkillTierView;
import ai.core.api.server.skill.UpdateSkillTierRequest;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillResource;
import ai.core.server.domain.SkillSourceType;
import ai.core.server.domain.SkillTier;
import ai.core.server.domain.User;
import ai.core.server.skill.quality.SkillCodeRuleEvaluator;
import ai.core.server.skill.quality.SkillDecisionQualityRule;
import ai.core.server.skill.quality.SkillQualityGate;
import ai.core.server.skill.quality.SkillQualityGateException;
import ai.core.server.skill.quality.SkillQualityPolicyService;
import ai.core.server.skill.quality.SkillQualityRuleRegistry;
import ai.core.server.skill.quality.SkillQualityStore;
import ai.core.server.skill.quality.domain.SkillQualityPolicyHead;
import ai.core.server.skill.quality.domain.SkillQualityPolicyRevision;
import ai.core.server.skill.quality.domain.SkillQualityRule;
import ai.core.server.skill.quality.domain.SkillQualityRuleRevision;
import ai.core.server.skillhub.SkillHubAccessPolicy;
import ai.core.server.skillhub.SkillHubService;
import ai.core.server.user.UserStore;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.ForbiddenException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressFBWarnings({"MRC_METHOD_RETURNS_CONSTANT", "RFI_SET_ACCESSIBLE", "NAB_NEEDLESS_BOOLEAN_CONSTANT_CONVERSION"})
class SkillTierAdminServiceTest {
    private SkillStore skillStore;
    private SkillService skillService;
    private SkillTierAdminService service;

    @BeforeEach
    void setUp() throws Exception {
        skillStore = mock(SkillStore.class);
        var userStore = mock(UserStore.class);
        skillService = mock(SkillService.class);

        var registry = new SkillQualityRuleRegistry();
        for (var rule : SkillQualityGate.builtInRules()) {
            registry.register(new SkillCodeRuleEvaluator(rule));
        }
        registry.register(new SkillDecisionQualityRule());

        var policyService = new SkillQualityPolicyService();
        inject(policyService, "store", new SimpleQualityStore());
        inject(policyService, "registry", registry);
        inject(policyService, "userStore", userStore);
        policyService.seed();

        var gate = new SkillQualityGate();
        inject(gate, "policyService", policyService);
        inject(gate, "registry", registry);

        var fixtureBuilder = new SkillQualityFixtureBuilder();
        fixtureBuilder.blobStore = new SkillBlobStore();

        service = new SkillTierAdminService();
        service.skillStore = skillStore;
        service.policyService = policyService;
        service.gate = gate;
        service.fixtureBuilder = fixtureBuilder;
        service.skillService = skillService;

        when(userStore.get("admin-1")).thenReturn(Optional.of(user("admin", "internal")));
        when(userStore.get("user-1")).thenReturn(Optional.of(user("user", "internal")));
        when(userStore.get("api-admin")).thenReturn(Optional.of(user("admin", "api")));
        when(skillStore.replaceIfRevision(any(), anyInt())).thenReturn(true);
    }

    @Test
    void rejectsNonAdminAndApiUsers() {
        var req = tierRequest(SkillTierView.CORE, "promote");
        assertThatThrownBy(() -> service.updateTier("s1", req, "user-1")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.updateTier("s1", req, "api-admin")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void promoteDomainToCoreRejectsWith422WhenEvalDatasetIsMissing() {
        var skill = skill("s1", SkillTier.DOMAIN, false);
        when(skillStore.get("s1")).thenReturn(Optional.of(skill));

        assertThatThrownBy(() -> service.updateTier("s1", tierRequest(SkillTierView.CORE, "promote"), "admin-1"))
            .isInstanceOf(SkillQualityGateException.class)
            .satisfies(ex -> {
                var gateEx = (SkillQualityGateException) ex;
                assertThat(gateEx.errorCode()).isEqualTo(SkillQualityGateException.REJECTED);
                assertThat(gateEx.status().code).isEqualTo(422);
                assertThat(gateEx.extraJson()).contains("SKILL.EVAL_DATASET");
            });
    }

    @Test
    void promoteDomainToCoreSucceedsWhenCoreGatePasses() {
        var skill = skill("s1", SkillTier.DOMAIN, true);
        when(skillStore.get("s1")).thenReturn(Optional.of(skill));

        var view = service.updateTier("s1", tierRequest(SkillTierView.CORE, "ready for core"), "admin-1");

        assertThat(view.tier).isEqualTo(SkillTierView.CORE);
        assertThat(skill.tier).isEqualTo(SkillTier.CORE);
        verify(skillService).invalidateCatalogCache();
    }

    @Test
    void rejectsDirectPromotionFromImportedToAlwaysOn() {
        var skill = skill("s1", SkillTier.IMPORTED, true);
        skill.sourceType = SkillSourceType.REPO;
        when(skillStore.get("s1")).thenReturn(Optional.of(skill));

        assertThatThrownBy(() -> service.updateTier("s1", tierRequest(SkillTierView.ALWAYS_ON, "skip core"), "admin-1"))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("IMPORTED");
    }

    @Test
    void promoteToAlwaysOnReturns409WhenQuotaExceeded() {
        var skill = skill("s11", SkillTier.CORE, true);
        when(skillStore.get("s11")).thenReturn(Optional.of(skill));
        when(skillStore.countByTier(SkillTier.ALWAYS_ON)).thenReturn(10L);

        assertThatThrownBy(() -> service.updateTier("s11", tierRequest(SkillTierView.ALWAYS_ON, "pin"), "admin-1"))
            .isInstanceOf(ConflictException.class)
            .satisfies(ex -> assertThat(((ConflictException) ex).errorCode()).isEqualTo(SkillTierAdminService.QUOTA_EXCEEDED_CODE));
    }

    @Test
    void demotionRequiresReasonAndSucceedsWithReason() {
        var skill = skill("s1", SkillTier.ALWAYS_ON, true);
        when(skillStore.get("s1")).thenReturn(Optional.of(skill));

        assertThatThrownBy(() -> service.updateTier("s1", tierRequest(SkillTierView.DOMAIN, "   "), "admin-1"))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("reason");

        var view = service.updateTier("s1", tierRequest(SkillTierView.DOMAIN, "high error rate"), "admin-1");
        assertThat(view.tier).isEqualTo(SkillTierView.DOMAIN);
    }

    @Test
    void hubAlwaysOnEndpointReturnsOnlyAlwaysOnSkills() throws Exception {
        var alwaysOn1 = skill("s1", SkillTier.ALWAYS_ON, true);
        alwaysOn1.qualifiedName = "builtin/browser-use";
        alwaysOn1.digest = "sha256:aaa";
        var alwaysOn2 = skill("s2", SkillTier.ALWAYS_ON, true);
        alwaysOn2.qualifiedName = "builtin/ai-camera-language";
        alwaysOn2.digest = "sha256:bbb";
        when(skillStore.listByTier(SkillTier.ALWAYS_ON)).thenReturn(List.of(alwaysOn1, alwaysOn2));

        var hub = new SkillHubService();
        inject(hub, "skillStore", skillStore);
        inject(hub, "accessPolicy", new SkillHubAccessPolicy());

        var response = hub.alwaysOn();
        assertThat(response.skills).hasSize(2);
        assertThat(response.skills.get(0).qualifiedName).isEqualTo("builtin/ai-camera-language");
        assertThat(response.skills.get(1).qualifiedName).isEqualTo("builtin/browser-use");
        assertThat(response.skills.get(1).digest).isEqualTo("sha256:aaa");
    }

    private void inject(Object target, String name, Object value) throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private UpdateSkillTierRequest tierRequest(SkillTierView tier, String reason) {
        var req = new UpdateSkillTierRequest();
        req.tier = tier;
        req.reason = reason;
        return req;
    }

    private User user(String role, String userType) {
        var u = new User();
        u.role = role;
        u.userType = userType;
        return u;
    }

    private SkillDefinition skill(String id, SkillTier tier, boolean withEval) {
        var s = new SkillDefinition();
        s.id = id;
        s.namespace = "team";
        s.name = "demo-skill";
        s.qualifiedName = "team/demo-skill";
        s.directoryName = "demo-skill";
        s.description = "Do demo things. Use when asked. Scope: demo only.";
        s.sourceType = SkillSourceType.UPLOAD;
        s.tier = tier;
        s.revision = 1;
        s.digest = "sha256:demo";
        s.userId = "author-1";
        s.content = """
            ---
            name: demo-skill
            description: "Do demo things. Use when asked. Scope: demo only."
            metadata:
              version: 1.0.0
              author: core-ai
              tags: demo
            ---
            ## Workflow
            1. Step one.
            ## Decision logic
            Decide.
            ## Output specification
            Output.
            ## Error handling
            Retry.
            ## Quality assurance
            Verify.
            """;
        if (withEval) {
            var evalRes = new SkillResource();
            evalRes.path = "eval/eval.json";
            evalRes.content = evalDataset();
            s.resources = List.of(evalRes);
        }
        return s;
    }

    private String evalDataset() {
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
        return "{\"schema_version\":1,\"skill\":\"demo-skill\",\"dataset_version\":\"1.0.0\",\"cases\":["
            + String.join(",", cases) + "]}";
    }

    private String evalCase(String id, String dimension, String expected) {
        return "{\"id\":\"" + id + "\",\"dimension\":\"" + dimension + "\","
            + "\"input\":{\"user_request\":\"run demo\"},"
            + "\"expected\":" + expected + ",\"assertions\":[{\"kind\":\"no_crash\"}]}";
    }

    private static final class SimpleQualityStore implements SkillQualityStore {
        private final Map<String, SkillQualityRule> rules = new LinkedHashMap<>();
        private final Map<String, SkillQualityRuleRevision> revisions = new LinkedHashMap<>();
        private final Map<Integer, SkillQualityPolicyRevision> policies = new LinkedHashMap<>();
        private final Map<String, SkillQualityPolicyHead> heads = new LinkedHashMap<>();

        @Override
        public Optional<SkillQualityRule> getRule(String id) {
            return Optional.ofNullable(rules.get(id));
        }

        @Override
        public Optional<SkillQualityRule> findRuleByKey(String key) {
            return rules.values().stream().filter(r -> key.equals(r.key)).findFirst();
        }

        @Override
        public List<SkillQualityRule> listRules() {
            return new ArrayList<>(rules.values());
        }

        @Override
        public void insertRule(SkillQualityRule rule) {
            rules.put(rule.id, rule);
        }

        @Override
        public boolean insertRuleIfAbsent(SkillQualityRule rule) {
            return rules.putIfAbsent(rule.id, rule) == null;
        }

        @Override
        public void replaceRule(SkillQualityRule rule) {
            rules.put(rule.id, rule);
        }

        @Override
        public Optional<SkillQualityRuleRevision> getRevision(String ruleId, int revision) {
            return Optional.ofNullable(revisions.get(ruleId + ":" + revision));
        }

        @Override
        public List<SkillQualityRuleRevision> listRevisions(String ruleId) {
            return revisions.values().stream().filter(r -> ruleId.equals(r.ruleId)).toList();
        }

        @Override
        public void insertRevision(SkillQualityRuleRevision revision) {
            revisions.put(revision.id, revision);
        }

        @Override
        public boolean insertRevisionIfAbsent(SkillQualityRuleRevision revision) {
            return revisions.putIfAbsent(revision.id, revision) == null;
        }

        @Override
        public void replaceRevision(SkillQualityRuleRevision revision) {
            revisions.put(revision.id, revision);
        }

        @Override
        public Optional<SkillQualityPolicyRevision> getPolicy(int revision) {
            return Optional.ofNullable(policies.get(revision));
        }

        @Override
        public List<SkillQualityPolicyRevision> listPolicies() {
            return policies.values().stream().sorted((a, b) -> Integer.compare(b.revision, a.revision)).toList();
        }

        @Override
        public List<SkillQualityPolicyRevision> listPolicies(String scope) {
            return policies.values().stream().filter(p -> scope.equals(p.scope))
                .sorted((a, b) -> Integer.compare(b.revision, a.revision)).toList();
        }

        @Override
        public void insertPolicy(SkillQualityPolicyRevision policy) {
            policies.put(policy.revision, policy);
        }

        @Override
        public boolean insertPolicyIfAbsent(SkillQualityPolicyRevision policy) {
            return policies.putIfAbsent(policy.revision, policy) == null;
        }

        @Override
        public Optional<SkillQualityPolicyHead> getHead(String scope) {
            return Optional.ofNullable(heads.get(scope));
        }

        @Override
        public boolean insertHeadIfAbsent(SkillQualityPolicyHead head) {
            return heads.putIfAbsent(head.scope, head) == null;
        }

        @Override
        public boolean replaceHead(SkillQualityPolicyHead head, int expectedGeneration) {
            var current = heads.get(head.scope);
            if (current == null || current.generation != expectedGeneration) return false;
            heads.put(head.scope, head);
            return true;
        }
    }
}
