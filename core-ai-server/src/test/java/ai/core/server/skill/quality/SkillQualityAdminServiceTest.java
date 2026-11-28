package ai.core.server.skill.quality;

import ai.core.api.server.skill.ActivateSkillQualityPolicyRequest;
import ai.core.api.server.skill.CreateSkillQualityRuleRequest;
import ai.core.api.server.skill.UpdateSkillQualityRuleRequest;
import ai.core.server.domain.SkillTier;
import ai.core.server.domain.User;
import ai.core.server.user.UserStore;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillQualityAdminServiceTest {
    private final UserStore userStore = mock(UserStore.class);
    private final InMemorySkillQualityStore store = new InMemorySkillQualityStore();
    private final SkillQualityRuleRegistry registry = new SkillQualityRuleRegistry();
    private SkillQualityAdminService service;

    @BeforeEach
    void setUp() {
        for (var rule : SkillQualityGate.builtInRules()) {
            registry.register(new SkillCodeRuleEvaluator(rule));
        }
        registry.register(new SkillDecisionQualityRule());

        var policyService = new SkillQualityPolicyService();
        policyService.store = store;
        policyService.registry = registry;
        policyService.userStore = userStore;
        policyService.seed();

        service = new SkillQualityAdminService();
        service.policyService = policyService;
        service.store = store;
        service.registry = registry;

        when(userStore.get("admin-1")).thenReturn(Optional.of(user("admin", "internal")));
    }

    @Test
    void returnsTheSeededCatalogToAnAdmin() {
        var response = service.rules("admin-1");

        assertThat(response.policyRevision).isPositive();
        assertThat(response.rules).hasSize(12);
        assertThat(response.rules).extracting(view -> view.key)
            .containsExactlyInAnyOrderElementsOf(SkillQualityGate.ruleKeys());
        assertThat(response.rules).allSatisfy(view -> {
            assertThat(view.id).isNotBlank();
            assertThat(view.name).isNotBlank();
            assertThat(view.category).isNotBlank();
            assertThat(view.enforcement).isNotBlank();
            assertThat(view.description).isNotBlank();
            assertThat(view.revision).isPositive();
            assertThat(view.builtIn).isTrue();
        });
    }

    @Test
    void createsAndRevisionsACustomDecisionRule() {
        var request = new CreateSkillQualityRuleRequest();
        request.key = "SKILL.CUSTOM.CLARITY";
        request.name = "Workflow clarity";
        request.description = "The workflow must name an ordered sequence of actions.";
        request.decisionQuestion = "Does the workflow name an ordered sequence of actions?";
        request.messageTemplate = "The workflow is not ordered.";
        request.remediationTemplate = "Number the steps and state the order.";
        request.inputSelector = List.of(SkillCustomRuleInput.SKILL_CONTENT);
        request.affirmativeThreshold = 0.85;
        request.negativeThreshold = 0.15;

        var created = service.createRule(request, "admin-1");

        assertThat(created.key).isEqualTo("SKILL.CUSTOM.CLARITY");
        assertThat(created.builtIn).isFalse();
        assertThat(created.lifecycle).isEqualTo("DRAFT");
        assertThat(created.revision).isEqualTo(1);
        assertThat(created.kind).isEqualTo("DECISION");
        assertThat(created.evaluatorKey).isEqualTo(SkillDecisionQualityRule.EVALUATOR_KEY);

        var update = new UpdateSkillQualityRuleRequest();
        update.decisionInstruction = "Only count steps that are explicitly ordered.";
        update.changeReason = "tighten the question";
        var revised = service.updateRule(created.id, update, "admin-1");

        assertThat(revised.revision).isEqualTo(2);
        assertThat(revised.decisionInstruction).isEqualTo("Only count steps that are explicitly ordered.");
        assertThat(revised.tested).isFalse();
    }

    @Test
    void builtInDefinitionsCannotBeEdited() {
        var update = new UpdateSkillQualityRuleRequest();
        update.name = "renamed";
        update.changeReason = "try to edit a code rule";

        assertThatThrownBy(() -> service.updateRule(SkillQualityPolicyService.ruleId("SKILL.NAME_IDENTITY"),
            update, "admin-1")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsNonAdminUsers() {
        when(userStore.get("user-1")).thenReturn(Optional.of(user("user", "internal")));
        when(userStore.get("api-1")).thenReturn(Optional.of(user("admin", "api")));

        assertThatThrownBy(() -> service.rules("user-1")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.rules("api-1")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.rules("missing")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void policiesRequireScopeAndSupportAllAndSingleScopeActivation() {
        assertThatThrownBy(() -> service.policies("admin-1", null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.policies("admin-1", "skill:server")).isInstanceOf(BadRequestException.class);

        var all = service.policies("admin-1", "all");
        assertThat(all.scopes).hasSize(4);

        var domain = service.policies("admin-1", SkillTier.SCOPE_DOMAIN);
        assertThat(domain.active.scope).isEqualTo(SkillTier.SCOPE_DOMAIN);

        var activate = new ActivateSkillQualityPolicyRequest();
        activate.scope = SkillTier.SCOPE_CORE;
        activate.expectedGeneration = 1;
        activate.changeReason = "relax eval for test";
        activate.ruleId = SkillQualityPolicyService.ruleId("SKILL.EVAL_DATASET");
        activate.ruleRevision = 1;
        activate.enforcement = "WARN";

        var missingScope = new ActivateSkillQualityPolicyRequest();
        missingScope.changeReason = "no scope";
        assertThatThrownBy(() -> service.activate(missingScope, "admin-1")).isInstanceOf(BadRequestException.class);

        var updated = service.activate(activate, "admin-1");
        assertThat(updated.scope).isEqualTo(SkillTier.SCOPE_CORE);
        assertThat(updated.generation).isEqualTo(2);
    }

    private User user(String role, String userType) {
        var user = new User();
        user.role = role;
        user.userType = userType;
        return user;
    }
}
