package ai.core.server.quality;

import ai.core.api.server.agent.AgentQualityReportView;
import ai.core.server.agent.AgentExecutableConfigFactory;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.quality.domain.AgentQualityRun;
import ai.core.server.quality.domain.QualityGateMode;
import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.quality.domain.QualityRunStatus;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Pass-through gate for tests that exercise call sites rather than rule evaluation.
 *
 * @author core-ai
 */
public final class PassThroughQualityGate implements PublishQualityGate {
    @Override
    public PublishCandidate buildCandidate(AgentDefinition entity) {
        int revision = entity.definitionRevision == null ? 0 : entity.definitionRevision;
        var config = AgentExecutableConfigFactory.fromEditableDefinition(entity);
        ai.core.server.agent.AgentDependencyAccessPolicy.markPublishedSkillsValidated(config);
        var prompt = new QualityPromptView(config.systemPrompt, config.inputTemplate,
            "INLINE", null, false, false, QualityHashes.textHash(config.systemPrompt));
        return new PublishCandidate(entity, config, entity.name, entity.description,
            QualityHashes.candidateHash(config), QualityHashes.metadataHash(entity.name, entity.description),
            revision, prompt, List.of(), "/system_prompt", "INLINE");
    }

    @Override
    public QualityRunResult evaluate(AgentDefinition entity, PublishCandidate candidate, String userId,
                                     QualityGateMode mode, String idempotencyKey, boolean enforce) {
        var run = new AgentQualityRun();
        run.id = "test-run";
        run.agentId = entity.id;
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
        report.gateResult = "PASSED";
        report.issues = List.of();
        return new QualityRunResult(run, report, false);
    }

    @Override
    public QualityRunResult precheck(AgentDefinition entity, String userId, String idempotencyKey) {
        return evaluate(entity, buildCandidate(entity), userId, QualityGateMode.PRECHECK, idempotencyKey, false);
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
}
