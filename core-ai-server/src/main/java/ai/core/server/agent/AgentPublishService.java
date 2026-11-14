package ai.core.server.agent;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentQualityReceipt;
import ai.core.server.domain.AgentStatus;
import ai.core.server.quality.PublishCandidate;
import ai.core.server.quality.PublishQualityGate;
import ai.core.server.quality.QualityGateException;
import ai.core.server.quality.QualityRunResult;
import ai.core.server.quality.domain.QualityGateMode;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;

/**
 * Owns the write side of a publish: one candidate, one gate run, one conditional update, one receipt.
 * Splitting it from {@link AgentDefinitionService} keeps the CRUD service small and makes the publish
 * invariants testable in one place.
 *
 * @author core-ai
 */
public class AgentPublishService {
    @Inject
    AgentDefinitionStore agentDefinitionStore;
    @Inject
    PublishQualityGate qualityGate;

    public AgentPublishResult publish(AgentDefinition entity, String userId, PublishOptions options) {
        var candidate = qualityGate.buildCandidate(entity);
        if (options.expectedRevision() != null && options.expectedRevision() != candidate.definitionRevision()) {
            throw QualityGateException.stale("Agent 已被修改，请刷新后重新发布。");
        }
        var result = qualityGate.evaluate(entity, candidate, userId, QualityGateMode.PUBLISH,
            options.idempotencyKey(), true);
        if (result.alreadyCommitted()) {
            return new AgentPublishResult(entity, result.runId(), result.warnCount());
        }
        qualityGate.recheck(candidate, result.run());
        applyPublish(entity, candidate, result, userId);
        if (!agentDefinitionStore.replaceIfRevision(entity, candidate.definitionRevision())) {
            throw QualityGateException.stale("Agent 在发布提交前被修改或删除，请刷新后重试。");
        }
        qualityGate.recordPublication(result.run(), candidate, userId);
        return new AgentPublishResult(entity, result.runId(), result.warnCount());
    }

    private void applyPublish(AgentDefinition entity, PublishCandidate candidate, QualityRunResult result,
                              String userId) {
        var now = ZonedDateTime.now();
        entity.publishedConfig = candidate.config();
        entity.publishedName = candidate.name();
        entity.publishedDescription = candidate.description();
        entity.metadataHash = candidate.metadataHash();
        entity.qualityReceipt = receipt(candidate, result, now);
        entity.status = AgentStatus.PUBLISHED;
        entity.publishedAt = now;
        entity.updatedAt = now;
        entity.updatedBy = userId;
    }

    private AgentQualityReceipt receipt(PublishCandidate candidate, QualityRunResult result, ZonedDateTime now) {
        var receipt = new AgentQualityReceipt();
        receipt.runId = result.runId();
        receipt.policyRevision = result.policyRevision();
        receipt.definitionRevision = candidate.definitionRevision() + 1;
        receipt.executionCandidateHash = candidate.executionCandidateHash();
        receipt.metadataHash = candidate.metadataHash();
        receipt.gateResult = result.run().gateResult == null ? null : result.run().gateResult.name();
        receipt.ruleResultDigest = result.run().ruleResultDigest;
        receipt.routeFingerprint = result.run().routeFingerprint;
        receipt.warnCount = result.warnCount();
        receipt.publishedAt = now;
        return receipt;
    }
}
