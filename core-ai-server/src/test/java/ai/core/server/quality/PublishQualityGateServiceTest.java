package ai.core.server.quality;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.quality.domain.AgentQualityPolicyHead;
import ai.core.server.quality.domain.AgentQualityPolicyRevision;
import ai.core.server.quality.domain.QualityGateMode;
import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.quality.domain.QualityRunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PublishQualityGateServiceTest {
    private final AgentQualityStore store = mock(AgentQualityStore.class);
    private final QualityPolicyService policyService = mock(QualityPolicyService.class);
    private final QualityRuleRegistry registry = mock(QualityRuleRegistry.class);
    private final QualitySnapshotService snapshotService = mock(QualitySnapshotService.class);
    private final PublishQualityGateService service = new PublishQualityGateService();

    @BeforeEach
    void setUp() {
        service.store = store;
        service.policyService = policyService;
        service.registry = registry;
        service.snapshotService = snapshotService;
        when(store.updateRun(any(), eq(QualityRunStatus.STARTED))).thenReturn(Boolean.TRUE);
    }

    @Test
    void builtInAgentPassesWithoutEvaluatingRules() {
        when(policyService.active()).thenReturn(activePolicy());
        var entity = new AgentDefinition();
        entity.id = "default-assistant";
        entity.systemDefault = Boolean.TRUE;

        var result = service.evaluate(entity, candidate(entity), "user-1", QualityGateMode.PUBLISH, null, true);

        assertThat(result.passed()).isTrue();
        assertThat(result.run().gateResult).isEqualTo(QualityGateResult.PASSED);
        assertThat(result.run().exempted).isTrue();
        assertThat(result.run().exemptionReason).isEqualTo(QualityExemptions.BUILT_IN_AGENT_REASON);
        assertThat(result.run().ruleResults).isEmpty();
        assertThat(result.run().issues).isEmpty();
        verifyNoInteractions(registry, snapshotService);
    }

    @Test
    void personalAssistantCopyPassesWithoutEvaluatingRules() {
        when(policyService.active()).thenReturn(activePolicy());
        var entity = new AgentDefinition();
        entity.id = "assistant:user-1";
        entity.forkedFrom = "default-assistant";

        var result = service.evaluate(entity, candidate(entity), "user-1", QualityGateMode.FORK, null, true);

        assertThat(result.passed()).isTrue();
        assertThat(result.run().exempted).isTrue();
        assertThat(result.run().exemptionReason).isEqualTo(QualityExemptions.PERSONAL_ASSISTANT_REASON);
        verifyNoInteractions(registry, snapshotService);
    }

    @Test
    void whitelistedAgentPassesWithoutEvaluatingRules() {
        when(policyService.active()).thenReturn(activePolicy("agent-1"));
        var entity = new AgentDefinition();
        entity.id = "agent-1";

        var result = service.evaluate(entity, candidate(entity), "user-1", QualityGateMode.PUBLISH, null, true);

        assertThat(result.passed()).isTrue();
        assertThat(result.run().exempted).isTrue();
        assertThat(result.run().exemptionReason)
            .isEqualTo(QualityExemptions.WHITELIST_REASON + ": agent-1");
        assertThat(result.report().exempted).isTrue();
        verifyNoInteractions(registry, snapshotService);
    }

    @Test
    void whitelistedAgentCanBeMatchedByName() {
        when(policyService.active()).thenReturn(activePolicy("Report Renderer"));
        var entity = new AgentDefinition();
        entity.id = "agent-9";
        entity.name = "Report Renderer";

        var result = service.evaluate(entity, candidate(entity), "user-1", QualityGateMode.PUBLISH, null, true);

        assertThat(result.run().exempted).isTrue();
        assertThat(result.run().exemptionReason)
            .isEqualTo(QualityExemptions.WHITELIST_REASON + ": Report Renderer");
    }

    @Test
    void ordinaryAgentStillRunsTheSnapshotPath() {
        when(policyService.active()).thenReturn(activePolicy());
        when(snapshotService.snapshot(any(), eq(7))).thenReturn(
            new PublishQualitySnapshot(null, List.of(), null, "fingerprint", "policy"));
        var entity = new AgentDefinition();
        entity.id = "agent-1";

        var result = service.evaluate(entity, candidate(entity), "user-1", QualityGateMode.PUBLISH, null, true);

        assertThat(result.passed()).isTrue();
        assertThat(result.run().exempted).isFalse();
        verify(snapshotService).snapshot(any(), eq(7));
    }

    private QualityPolicyService.ActivePolicy activePolicy(String... exemptions) {
        var policy = new AgentQualityPolicyRevision();
        policy.id = "policy:7";
        policy.revision = 7;
        policy.scope = QualityPolicyService.SCOPE_SERVER;
        policy.exemptions = List.of(exemptions);
        var head = new AgentQualityPolicyHead();
        head.id = QualityPolicyService.SCOPE_SERVER;
        head.scope = QualityPolicyService.SCOPE_SERVER;
        head.activeRevision = 7;
        head.generation = 3;
        return new QualityPolicyService.ActivePolicy(policy, head, List.of());
    }

    private PublishCandidate candidate(AgentDefinition entity) {
        return new PublishCandidate(entity, new AgentPublishedConfig(), entity.name, entity.description,
            "sha256:candidate", "sha256:metadata", 1, null, List.of(), "/system_prompt", "INLINE");
    }
}
