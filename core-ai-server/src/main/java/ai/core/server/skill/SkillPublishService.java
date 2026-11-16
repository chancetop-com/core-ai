package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillQualityReceipt;
import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.skill.quality.SkillPublishCandidate;
import ai.core.server.skill.quality.SkillPublishCandidateBuilder;
import ai.core.server.skill.quality.SkillQualityGate;
import ai.core.server.skill.quality.SkillQualityGateException;
import ai.core.server.skill.quality.SkillQualityReport;
import ai.core.server.skill.quality.SkillQualityResult;
import core.framework.inject.Inject;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The only write path for skill packages. It builds one candidate, runs the gate on it, then projects the
 * entity from that same candidate. Resource blobs are written only after the gate passes.
 *
 * @author core-ai
 */
public class SkillPublishService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillPublishService.class);

    @Inject
    SkillStore skillStore;

    @Inject
    SkillBlobStore blobStore;

    private final SkillQualityGate gate = new SkillQualityGate();

    public SkillPublishOutcome publish(SkillPublishRequest request) {
        var content = request.content != null ? request.content : existingContent(request.existing);
        var existing = request.existing;
        var candidate = new SkillPublishCandidateBuilder().build(
            request.namespace, directoryName(request), content, request.resources, existing);
        verifyMirrors(request, candidate);
        verifyImmutableIdentity(existing, candidate);
        var result = gate.evaluate(candidate);
        var report = SkillQualityReport.of(candidate, result);
        if (!result.passed()) throw failure(result, report);

        var previous = existing == null ? null : existing.resources;
        var expected = existing == null || existing.revision == null ? 0 : existing.revision;
        var entity = project(request, candidate, result);
        write(entity, existing, expected);
        blobStore.deleteReplaced(previous, entity.resources);
        LOGGER.info("published skill, id={}, qualifiedName={}, revision={}, gate={}, warns={}",
            entity.id, entity.qualifiedName, entity.revision, result.gateResult(), result.warnCount());
        return new SkillPublishOutcome(entity, report);
    }

    private void verifyMirrors(SkillPublishRequest request, SkillPublishCandidate candidate) {
        if (request.mirroredDescription != null && !request.mirroredDescription.equals(candidate.description)) {
            throw SkillQualityGateException.metadataMismatch("description");
        }
        if (request.mirroredAllowedTools != null
            && !normalized(request.mirroredAllowedTools).equals(normalized(candidate.allowedTools))) {
            throw SkillQualityGateException.metadataMismatch("allowed_tools");
        }
    }

    private void verifyImmutableIdentity(SkillDefinition existing, SkillPublishCandidate candidate) {
        if (existing == null) return;
        if (!candidate.name.equals(existing.name)) throw SkillQualityGateException.nameImmutable();
        var storedDirectory = existing.directoryName != null ? existing.directoryName : existing.name;
        if (!candidate.name.equals(storedDirectory)) throw SkillQualityGateException.nameImmutable();
    }

    private SkillDefinition project(SkillPublishRequest request, SkillPublishCandidate candidate,
                                    SkillQualityResult result) {
        var entity = request.existing == null ? new SkillDefinition() : request.existing;
        if (entity.id == null) {
            entity.id = new ObjectId().toHexString();
            entity.createdAt = ZonedDateTime.now();
        }
        entity.namespace = request.namespace;
        entity.name = candidate.name;
        entity.qualifiedName = candidate.qualifiedName();
        entity.directoryName = candidate.directoryName;
        entity.description = candidate.description;
        entity.sourceType = request.sourceType;
        entity.content = candidate.content;
        entity.resources = new SkillResourceWriter(blobStore).toResources(entity.id, candidate.content, candidate.resources);
        entity.allowedTools = candidate.allowedTools.isEmpty() ? null : new ArrayList<>(candidate.allowedTools);
        entity.metadata = candidate.metadata.isEmpty() ? null : Map.copyOf(candidate.metadata);
        entity.version = candidate.version;
        entity.userId = request.userId;
        entity.digest = candidate.packageDigest;
        entity.revision = candidate.revision;
        entity.updatedAt = ZonedDateTime.now();
        if (request.repoConfig != null) entity.repoConfig = request.repoConfig;
        entity.qualityReceipt = receipt(candidate, result);
        return entity;
    }

    private SkillQualityReceipt receipt(SkillPublishCandidate candidate, SkillQualityResult result) {
        var receipt = new SkillQualityReceipt();
        receipt.runId = result.runId();
        receipt.policyRevision = SkillQualityGate.POLICY_REVISION;
        receipt.revision = candidate.revision;
        receipt.packageDigest = candidate.packageDigest;
        receipt.metadataHash = candidate.metadataHash;
        receipt.gateResult = QualityGateResult.PASSED;
        receipt.checksComplete = result.checksComplete();
        receipt.warnCount = result.warnCount();
        receipt.ruleResultDigest = result.ruleResultDigest();
        receipt.checkedAt = ZonedDateTime.now();
        return receipt;
    }

    private void write(SkillDefinition entity, SkillDefinition existing, int expected) {
        if (existing == null) {
            if (!skillStore.insertIfAbsent(entity)) {
                throw SkillQualityGateException.stale("同名 skill 已被其他请求创建，请刷新后重试。");
            }
            return;
        }
        if (!skillStore.replaceIfRevision(entity, expected)) {
            throw SkillQualityGateException.stale("Skill 在检查后被其他请求修改，请刷新后重试。");
        }
    }

    private SkillQualityGateException failure(SkillQualityResult result, SkillQualityReport report) {
        return result.gateResult() == QualityGateResult.REJECTED
            ? SkillQualityGateException.rejected(report)
            : SkillQualityGateException.unavailable(report);
    }

    private String existingContent(SkillDefinition existing) {
        if (existing == null || existing.content == null) {
            throw new core.framework.web.exception.BadRequestException(
                "SKILL.md content is required", "SKILL_DOCUMENT_INVALID");
        }
        return existing.content;
    }

    private String directoryName(SkillPublishRequest request) {
        if (request.directoryName != null && !request.directoryName.isBlank()) return request.directoryName;
        if (request.existing != null && request.existing.directoryName != null) return request.existing.directoryName;
        if (request.existing != null) return request.existing.name;
        throw SkillQualityGateException.directoryRequired();
    }

    private List<String> normalized(List<String> values) {
        if (values == null) return List.of();
        return values.stream().map(value -> value == null ? "" : value.trim())
            .filter(value -> !value.isEmpty()).sorted().toList();
    }
}
