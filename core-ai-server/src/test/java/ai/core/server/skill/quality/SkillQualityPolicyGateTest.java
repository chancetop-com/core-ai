package ai.core.server.skill.quality;

import ai.core.api.server.skill.CreateSkillQualityRuleRequest;
import ai.core.decision.DecisionAnswer;
import ai.core.decision.DecisionRoute;
import ai.core.decision.DecisionType;
import ai.core.server.domain.User;
import ai.core.server.quality.QualityDecisionGateway;
import ai.core.server.quality.ValidatedDecision;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.user.UserStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressFBWarnings("MRC_METHOD_RETURNS_CONSTANT")
class SkillQualityPolicyGateTest {
    private static final String QUESTION = "Is the workflow concrete enough for a reader to execute?";

    private final UserStore userStore = mock(UserStore.class);
    private final InMemorySkillQualityStore store = new InMemorySkillQualityStore();
    private final SkillQualityRuleRegistry registry = new SkillQualityRuleRegistry();
    private final QualityDecisionGateway decisions = mock(QualityDecisionGateway.class);
    private SkillQualityPolicyService policyService;
    private SkillQualityGate gate;
    private String customRuleId;

    @BeforeEach
    void setUp() {
        for (var rule : SkillQualityGate.builtInRules()) {
            registry.register(new SkillCodeRuleEvaluator(rule));
        }
        registry.register(new SkillDecisionQualityRule());

        policyService = new SkillQualityPolicyService();
        policyService.store = store;
        policyService.registry = registry;
        policyService.userStore = userStore;
        policyService.seed();

        when(userStore.get("admin-1")).thenReturn(Optional.of(user("admin")));
        var admin = new SkillQualityAdminService();
        admin.policyService = policyService;
        admin.store = store;
        admin.registry = registry;

        var request = new CreateSkillQualityRuleRequest();
        request.key = "SKILL.CUSTOM.WORKFLOW";
        request.name = "Executable workflow";
        request.description = "The workflow must be concrete enough to execute.";
        request.decisionQuestion = QUESTION;
        request.messageTemplate = "The workflow is too vague to execute.";
        request.remediationTemplate = "Name the concrete actions and their order.";
        request.inputSelector = List.of(SkillCustomRuleInput.SKILL_CONTENT);
        customRuleId = admin.createRule(request, "admin-1").id;

        gate = new SkillQualityGate();
        gate.policyService = policyService;
        gate.registry = registry;
        gate.setDecisions(decisions);
    }

    @Test
    void passingCustomRuleUsesTheActivePolicyRevision() {
        activate(QualityEnforcement.BLOCK);
        when(decisions.decide(any(), any(), anyList(), any(), any()))
            .thenReturn(decision(0.92));

        var result = gate.evaluate(candidate());

        assertThat(result.gateResult()).isEqualTo(QualityGateResult.PASSED);
        assertThat(result.policyRevision()).isEqualTo(2);
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void failingBlockCustomRuleRejectsThePublish() {
        activate(QualityEnforcement.BLOCK);
        when(decisions.decide(any(), any(), anyList(), any(), any()))
            .thenReturn(decision(0.05));

        var result = gate.evaluate(candidate());

        assertThat(result.gateResult()).as(result.issues().toString()).isEqualTo(QualityGateResult.REJECTED);
        assertThat(result.issues()).anySatisfy(issue -> {
            assertThat(issue.ruleId).isEqualTo("SKILL.CUSTOM.WORKFLOW");
            assertThat(issue.status).isEqualTo(ai.core.server.quality.domain.QualityIssueStatus.FAIL);
            assertThat(issue.enforcement).isEqualTo(QualityEnforcement.BLOCK);
            assertThat(issue.message).isEqualTo("The workflow is too vague to execute.");
        });
    }

    @Test
    void warnCustomRuleReportsWithoutBlocking() {
        activate(QualityEnforcement.WARN);
        when(decisions.decide(any(), any(), anyList(), any(), any()))
            .thenReturn(decision(0.05));

        var result = gate.evaluate(candidate());

        assertThat(result.gateResult()).isEqualTo(QualityGateResult.PASSED);
        assertThat(result.warnCount()).isEqualTo(1);
    }

    @Test
    void whitelistedSkillSkipsRuleEvaluationByQualifiedName() {
        activateExemptions(List.of("demo/demo-skill"));

        var result = gate.evaluate(candidate());

        assertThat(result.gateResult()).isEqualTo(QualityGateResult.PASSED);
        assertThat(result.exempted()).isTrue();
        assertThat(result.exemptionReason()).isEqualTo("quality exemption whitelist: demo/demo-skill");
        assertThat(result.policyRevision()).isEqualTo(2);
        assertThat(result.issues()).isEmpty();
        assertThat(result.ruleResults()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(decisions);
    }

    @Test
    void whitelistedSkillMatchesTheBareNameToo() {
        activateExemptions(List.of("demo-skill"));

        var result = gate.evaluate(candidate());

        assertThat(result.exempted()).isTrue();
        assertThat(result.exemptionReason()).isEqualTo("quality exemption whitelist: demo-skill");
    }

    @Test
    void laterPolicyChangesKeepTheExemptions() {
        activateExemptions(List.of("demo/demo-skill"));
        policyService.createPolicy(new SkillQualityPolicyService.ActivateCommand(null, customRuleId, 1,
            QualityEnforcement.SHADOW, 2, "observe only", null, null, null), "admin-1");

        assertThat(policyService.active().exemptions()).containsExactly("demo/demo-skill");
    }

    @Test
    void decisionErrorFailsClosed() {
        activate(QualityEnforcement.BLOCK);
        when(decisions.decide(any(), any(), anyList(), any(), any()))
            .thenThrow(new ai.core.server.quality.QualityDecisionException(
                ai.core.server.quality.QualityDecisionException.Kind.PROVIDER, "provider down", true, null));

        var result = gate.evaluate(candidate());

        assertThat(result.gateResult()).isEqualTo(QualityGateResult.UNAVAILABLE);
        assertThat(result.issues()).anySatisfy(issue -> {
            assertThat(issue.ruleId).isEqualTo("SKILL.CUSTOM.WORKFLOW");
            assertThat(issue.status).isEqualTo(ai.core.server.quality.domain.QualityIssueStatus.ERROR);
        });
    }

    private void activate(QualityEnforcement enforcement) {
        policyService.createPolicy(new SkillQualityPolicyService.ActivateCommand(null, customRuleId, 1,
            enforcement, 1, "test policy", null, null, null), "admin-1");
    }

    private void activateExemptions(List<String> exemptions) {
        policyService.createPolicy(new SkillQualityPolicyService.ActivateCommand(null, null, null,
            null, 1, "exemption whitelist", null, null, exemptions), "admin-1");
    }

    private ValidatedDecision decision(double probability) {
        var answer = new DecisionAnswer(QUESTION, DecisionType.NOUL, probability, null, null,
            Map.of("yes", probability, "no", 1 - probability), null, Map.of());
        return new ValidatedDecision(Map.of(QUESTION, answer), List.of(),
            new DecisionRoute("test", "test-model", "test-fingerprint"), "test-model", 0, 0);
    }

    private User user(String role) {
        var user = new User();
        user.role = role;
        user.userType = "internal";
        return user;
    }

    private SkillPublishCandidate candidate() {
        var resources = new LinkedHashMap<String, byte[]>();
        resources.put("README.txt", "demo".getBytes(StandardCharsets.UTF_8));
        resources.put("eval/eval.json", evalDataset().getBytes(StandardCharsets.UTF_8));
        return new SkillPublishCandidateBuilder().build("demo", "demo-skill", skillContent(), resources, null);
    }

    private String skillContent() {
        return """
            ---
            name: demo-skill
            description: "Do demo things. Use when the user asks for a demo. Scope: demo inputs only; not for production."
            metadata:
              version: 1.0.0
              author: core-ai
              tags: demo,test
            ---
            # Demo skill

            ## Workflow
            1. Read the request.
            2. Produce the demo artifact.

            ## Decision making
            If the request is outside the demo scope, refuse. Otherwise continue.

            ## Output specification
            Return one Markdown file named demo.md.

            ## Error handling
            If the input is missing, ask the user; if the tool fails, retry once and then report the failure.

            ## Quality assurance
            Run `python eval/run.py --dataset eval/eval.json`; all seven dimensions must report pass.
            """;
    }

    private String evalDataset() {
        var cases = List.of(
            evalCase("trigger-001", "trigger_accuracy", "{\"triggered\":true}", "run demo"),
            evalCase("trigger-002", "trigger_accuracy", "{\"triggered\":false}", "unrelated question, no demo"),
            evalCase("scope-001", "scope_accuracy",
                "{\"in_scope\":true,\"expected_behavior\":\"handle\"}", "demo this input"),
            evalCase("tool-001", "tool_accuracy", "{\"no_tools\":true}", "answer without tools"),
            evalCase("param-001", "parameter_accuracy",
                "{\"tool\":\"read_file\",\"arguments\":{},\"match\":\"schema\"}", "read the input"),
            evalCase("instr-001", "instruction_following", "{\"rules\":[\"keep one file\"]}", "follow the rule"),
            evalCase("output-001", "output_quality", "{\"outputs\":[]}", "produce demo.md"),
            evalCase("robust-001", "robustness",
                "{\"failure_mode\":\"missing input\",\"expected_behavior\":\"ask_user\"}", "input is absent")
        );
        return "{\"schema_version\":1,\"skill\":\"demo-skill\",\"dataset_version\":\"1.0.0\",\"cases\":["
            + String.join(",", cases) + "]}";
    }

    private String evalCase(String id, String dimension, String expected, String request) {
        return "{\"id\":\"" + id + "\",\"dimension\":\"" + dimension + "\","
            + "\"input\":{\"user_request\":\"" + request + "\"},"
            + "\"expected\":" + expected + ",\"assertions\":[{\"kind\":\"no_crash\"}]}";
    }
}
