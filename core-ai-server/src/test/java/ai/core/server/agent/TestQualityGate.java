package ai.core.server.agent;

import ai.core.api.server.agent.AgentQualityReportView;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.quality.PublishCandidate;
import ai.core.server.quality.PublishQualityGate;
import ai.core.server.quality.QualityHashes;
import ai.core.server.quality.QualityPromptLine;
import ai.core.server.quality.QualityPromptView;
import ai.core.server.quality.QualityRunResult;
import ai.core.server.quality.domain.AgentQualityRun;
import ai.core.server.quality.domain.QualityGateMode;
import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.quality.domain.QualityRunStatus;
import ai.core.server.systemprompt.SystemPromptService;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Permissive gate for CRUD tests that are not about quality evaluation.
 *
 * @author core-ai
 */
final class TestQualityGate implements PublishQualityGate {
    private final SystemPromptService systemPromptService;

    TestQualityGate(SystemPromptService systemPromptService) {
        this.systemPromptService = systemPromptService;
    }

    @Override
    public PublishCandidate buildCandidate(AgentDefinition entity) {
        int revision = entity.definitionRevision == null ? 0 : entity.definitionRevision;
        var config = AgentExecutableConfigFactory.fromEditableDefinition(entity);
        AgentDependencyAccessPolicy.markPublishedSkillsValidated(config);
        if (config.systemPromptId != null) {
            config.systemPrompt = systemPromptService.resolveContent(config.systemPromptId);
            config.systemPromptId = null;
        }
        var prompt = new QualityPromptView(config.systemPrompt, config.inputTemplate,
            "INLINE", null, false, false, QualityHashes.textHash(config.systemPrompt));
        return new PublishCandidate(entity, config, entity.name, entity.description,
            QualityHashes.candidateHash(config), QualityHashes.metadataHash(entity.name, entity.description),
            revision, prompt, List.<QualityPromptLine>of(), "/system_prompt", "INLINE");
    }

    @Override
    public QualityRunResult evaluate(AgentDefinition entity, PublishCandidate candidate, String userId,
                                     QualityGateMode mode, String idempotencyKey, boolean enforce) {
        return pass(candidate);
    }

    @Override
    public QualityRunResult precheck(AgentDefinition entity, String userId, String idempotencyKey) {
        return pass(buildCandidate(entity));
    }

    @Override
    public void recheck(PublishCandidate candidate, AgentQualityRun run) {
    }

    @Override
    public void recordPublication(AgentQualityRun run, PublishCandidate candidate, String userId) {
    }

    @Override
    public AgentQualityReportView report(String agentId, String runId) {
        return null;
    }

    private QualityRunResult pass(PublishCandidate candidate) {
        var run = new AgentQualityRun();
        run.id = "test-run";
        run.agentId = candidate.entity().id;
        run.definitionRevision = candidate.definitionRevision();
        run.executionCandidateHash = candidate.executionCandidateHash();
        run.metadataHash = candidate.metadataHash();
        run.policyRevision = 1;
        run.runStatus = QualityRunStatus.COMPLETED;
        run.gateResult = QualityGateResult.PASSED;
        run.checksComplete = true;
        run.createdAt = ZonedDateTime.now();
        run.finishedAt = run.createdAt;
        var report = new AgentQualityReportView();
        report.runId = run.id;
        report.agentId = run.agentId;
        report.gateResult = QualityGateResult.PASSED.name();
        report.checksComplete = true;
        report.warnCount = 0;
        report.blockFailCount = 0;
        report.incompleteCount = 0;
        report.issues = List.of();
        return new QualityRunResult(run, report, false);
    }
}
