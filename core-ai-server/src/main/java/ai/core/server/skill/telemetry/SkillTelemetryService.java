package ai.core.server.skill.telemetry;

import ai.core.api.server.skill.ListSkillPapercutsResponse;
import ai.core.api.server.skill.ReportSkillPapercutRequest;
import ai.core.api.server.skill.SkillPapercutView;
import ai.core.api.server.skill.SkillTelemetryResponse;
import ai.core.api.server.skill.UpdateSkillPapercutStatusRequest;
import ai.core.api.server.skillhub.SkillTelemetryBatchItemRequest;
import ai.core.api.server.skillhub.SkillTelemetryBatchRequest;
import ai.core.api.server.skillhub.SkillTelemetryBatchResponse;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.skill.SkillStore;
import ai.core.server.skillhub.SkillHubAccessPolicy;
import ai.core.server.user.UserStore;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Orchestrates skill runtime telemetry ingestion, health readouts, papercut deduplication,
 * sanitization, privacy filtering, and owner/admin lifecycle transitions.
 *
 * @author stephen
 */
public class SkillTelemetryService {
    public static final int MAX_BATCH_ITEMS = 50;
    public static final long MAX_DELTA_PER_ITEM = 100L;
    public static final int MIN_SUMMARY_LENGTH = 10;
    public static final int BATCH_RETENTION_HOURS = 24;
    public static final int CLOSED_PAPERCUT_RETENTION_DAYS = 90;

    private static final Logger LOGGER = LoggerFactory.getLogger(SkillTelemetryService.class);
    private static final Pattern BATCH_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    @Inject
    SkillTelemetryStore telemetryStore;
    @Inject
    SkillStore skillStore;
    @Inject
    SkillHubAccessPolicy accessPolicy;
    @Inject
    UserStore userStore;

    public SkillTelemetryBatchResponse recordBatch(SkillTelemetryBatchRequest request, String actorUserId) {
        if (request == null || request.batchId == null || !BATCH_ID_PATTERN.matcher(request.batchId.trim()).matches()) {
            LOGGER.warn("skill telemetry batch rejected: invalid batchId, actorUserId={}", actorUserId);
            throw new BadRequestException("batch_id must be 8-128 characters matching [A-Za-z0-9._:-]");
        }
        if (request.items == null) {
            throw new BadRequestException("items is required");
        }
        var batchId = request.batchId.trim();
        if (request.items.size() > MAX_BATCH_ITEMS) {
            LOGGER.warn("skill telemetry batch rejected: item count {} exceeds limit {}, actorUserId={}, batchId={}",
                    request.items.size(), MAX_BATCH_ITEMS, actorUserId, batchId);
            throw new BadRequestException("telemetry batch exceeds max items: " + MAX_BATCH_ITEMS);
        }

        var collected = collectDeltas(request.items, actorUserId, batchId);
        var response = new SkillTelemetryBatchResponse();
        response.droppedUnknownItems = collected.droppedUnknown();
        if (!telemetryStore.isAvailable()) {
            response.accepted = Boolean.FALSE;
            response.duplicate = Boolean.FALSE;
            response.recordedItems = 0;
            return response;
        }
        var expiresAt = ZonedDateTime.now().plusHours(BATCH_RETENTION_HOURS);
        boolean recorded = telemetryStore.recordBatch(batchId, expiresAt, collected.deltas());
        response.accepted = Boolean.TRUE;
        response.duplicate = !recorded;
        response.recordedItems = recorded ? collected.deltas().size() : 0;
        return response;
    }

    public SkillTelemetryResponse getTelemetry(String skillId) {
        var skill = requireViewableSkill(skillId);
        var stats = telemetryStore.getStats(skill.id);
        int openPapercuts = telemetryStore.listPapercuts(skill.id, SkillPapercutStatus.OPEN).size();

        long totalInvoke = 0L;
        long totalResourceRead = 0L;
        long totalMaterializeFail = 0L;
        long totalScriptCalls = 0L;
        long totalScriptErrors = 0L;
        ZonedDateTime latestInvokedAt = null;

        for (var record : stats) {
            totalInvoke += record.invokeCount();
            totalResourceRead += record.resourceReadCount();
            totalMaterializeFail += record.materializeFailCount();
            totalScriptCalls += record.attributedScriptCalls();
            totalScriptErrors += record.attributedScriptErrors();
            if (record.lastInvokedAt() != null
                    && (latestInvokedAt == null || record.lastInvokedAt().isAfter(latestInvokedAt))) {
                latestInvokedAt = record.lastInvokedAt();
            }
        }

        var response = new SkillTelemetryResponse();
        response.skillId = skill.id;
        response.qualifiedName = skill.qualifiedName;
        response.currentDigest = skill.digest;
        response.telemetryAvailable = telemetryStore.isAvailable();
        response.totalInvokeCount = totalInvoke;
        response.totalResourceReadCount = totalResourceRead;
        response.totalMaterializeFailCount = totalMaterializeFail;
        response.totalAttributedScriptCalls = totalScriptCalls;
        response.totalAttributedScriptErrors = totalScriptErrors;
        response.openPapercutCount = openPapercuts;
        response.lastInvokedAt = latestInvokedAt;
        response.versions = stats.stream().map(SkillTelemetryMapper::toStatView).toList();
        return response;
    }

    public ListSkillPapercutsResponse listPapercuts(String skillId, String statusParam, String callerUserId) {
        var skill = requireViewableSkill(skillId);
        SkillPapercutStatus filter = null;
        if (statusParam != null && !statusParam.isBlank()) {
            filter = SkillPapercutStatus.parse(statusParam);
            if (filter == null) {
                throw new BadRequestException("invalid papercut status: " + statusParam);
            }
        }
        boolean privileged = isOwnerOrHumanAdmin(skill, callerUserId);
        var records = telemetryStore.listPapercuts(skill.id, filter);
        var response = new ListSkillPapercutsResponse();
        response.telemetryAvailable = telemetryStore.isAvailable();
        response.papercuts = records.stream()
                .map(record -> SkillTelemetryMapper.toPapercutView(record, privileged))
                .toList();
        return response;
    }

    public SkillPapercutView reportPapercut(String skillId, ReportSkillPapercutRequest request, String callerUserId) {
        var skill = requireViewableSkill(skillId);
        if (request == null || request.category == null) {
            throw new BadRequestException("category is required");
        }
        var command = new ReportPapercutCommand(
                SkillTelemetryMapper.toCategory(request.category),
                request.summary,
                request.observedError,
                request.suggestedFix,
                request.packageDigest,
                callerUserId,
                request.sessionId);
        var record = recordPapercut(skill, command);
        return SkillTelemetryMapper.toPapercutView(record, isOwnerOrHumanAdmin(skill, callerUserId));
    }

    public SkillPapercutRecord recordPapercut(SkillDefinition skill, ReportPapercutCommand command) {
        if (!telemetryStore.isAvailable()) {
            throw new ConflictException("skill papercut reporting requires PostgreSQL store mode",
                    "TELEMETRY_UNAVAILABLE");
        }
        if (command == null || command.category() == null) {
            throw new BadRequestException("category is required");
        }
        var sanitizedSummary = SkillTelemetrySanitizer.sanitizeSummary(command.summary());
        if (sanitizedSummary == null || sanitizedSummary.length() < MIN_SUMMARY_LENGTH) {
            throw new BadRequestException("summary must be between 10 and 200 characters");
        }
        var sanitizedError = SkillTelemetrySanitizer.sanitizeObservedError(command.observedError());
        var sanitizedFix = SkillTelemetrySanitizer.sanitizeSuggestedFix(command.suggestedFix());
        var digest = resolveDigest(command.packageDigest(), skill);

        var input = new SkillPapercutInput(
                skill.id,
                skill.qualifiedName,
                digest,
                command.category(),
                sanitizedSummary,
                sanitizedError,
                sanitizedFix,
                command.reporterUserId(),
                command.sessionId());
        return telemetryStore.upsertPapercut(input);
    }

    public SkillPapercutView updatePapercutStatus(String skillId, String papercutId,
                                                  UpdateSkillPapercutStatusRequest request, String callerUserId) {
        if (request == null || request.status == null) {
            throw new BadRequestException("status is required");
        }
        var skill = skillStore.get(skillId)
                .orElseThrow(() -> new NotFoundException("skill not found, id=" + skillId));
        if (!isOwnerOrHumanAdmin(skill, callerUserId)) {
            throw new ForbiddenException("only the skill owner or a human platform admin can update papercut status");
        }
        if (!telemetryStore.isAvailable()) {
            throw new ConflictException("skill papercut store is not available", "TELEMETRY_UNAVAILABLE");
        }
        var existing = telemetryStore.getPapercut(skill.id, papercutId)
                .orElseThrow(() -> new NotFoundException("papercut not found, id=" + papercutId));
        var targetStatus = SkillTelemetryMapper.toStatus(request.status);
        var updated = telemetryStore.updatePapercutStatus(skill.id, papercutId, targetStatus, callerUserId)
                .orElseThrow(() -> new NotFoundException("papercut not found, id=" + papercutId));
        auditStatusChange(callerUserId, skill.id, papercutId, existing.status(), updated.status());
        return SkillTelemetryMapper.toPapercutView(updated, true);
    }

    public CleanupResult purgeExpiredData() {
        if (!telemetryStore.isAvailable()) {
            return new CleanupResult(0, 0);
        }
        var now = ZonedDateTime.now();
        int purgedBatches = telemetryStore.purgeExpiredBatches(now);
        int purgedPapercuts = telemetryStore.purgeClosedPapercutsBefore(now.minusDays(CLOSED_PAPERCUT_RETENTION_DAYS));
        return new CleanupResult(purgedBatches, purgedPapercuts);
    }

    private CollectedBatch collectDeltas(List<SkillTelemetryBatchItemRequest> items, String actorUserId, String batchId) {
        var deltas = new ArrayList<SkillTelemetryDelta>(items.size());
        int droppedUnknown = 0;
        for (var item : items) {
            if (item == null) {
                droppedUnknown++;
                continue;
            }
            var validated = validateItemDeltas(item, actorUserId, batchId);
            var skill = resolveSkill(item);
            if (skill == null) {
                droppedUnknown++;
                continue;
            }
            var delta = new SkillTelemetryDelta(
                    skill.id,
                    resolveDigest(item.packageDigest, skill),
                    validated.invoke(),
                    validated.resourceRead(),
                    validated.materializeFail(),
                    validated.attributedScriptCalls(),
                    validated.attributedScriptErrors(),
                    item.lastInvokedAt);
            if (!delta.isZero()) {
                deltas.add(delta);
            }
        }
        return new CollectedBatch(deltas, droppedUnknown);
    }

    private String resolveDigest(String requestedDigest, SkillDefinition skill) {
        if (requestedDigest != null && !requestedDigest.isBlank()) {
            return requestedDigest.trim();
        }
        return skill.digest == null ? "" : skill.digest;
    }

    private SkillDefinition requireViewableSkill(String skillId) {
        var skill = skillStore.get(skillId)
                .orElseThrow(() -> new NotFoundException("skill not found, id=" + skillId));
        if (!accessPolicy.canView(skill)) {
            throw new ForbiddenException("skill is not accessible, id=" + skillId);
        }
        return skill;
    }

    private boolean isOwnerOrHumanAdmin(SkillDefinition skill, String callerUserId) {
        if (callerUserId == null || callerUserId.isBlank()) return false;
        if (callerUserId.equals(skill.userId)) return true;
        var user = userStore.get(callerUserId).orElse(null);
        return user != null && "admin".equals(user.role) && !"api".equals(user.userType);
    }

    private SkillDefinition resolveSkill(SkillTelemetryBatchItemRequest item) {
        if (item.skillId != null && !item.skillId.isBlank()) {
            return skillStore.get(item.skillId.trim()).orElse(null);
        }
        if (item.qualifiedName != null && !item.qualifiedName.isBlank()) {
            return skillStore.findByQualifiedName(item.qualifiedName.trim()).orElse(null);
        }
        return null;
    }

    private ValidatedDeltas validateItemDeltas(SkillTelemetryBatchItemRequest item, String actorUserId, String batchId) {
        long invoke = checkDelta("invoke_delta", item.invokeDelta, actorUserId, batchId);
        long resourceRead = checkDelta("resource_read_delta", item.resourceReadDelta, actorUserId, batchId);
        long materializeFail = checkDelta("materialize_fail_delta", item.materializeFailDelta, actorUserId, batchId);
        long scriptCalls = checkDelta("attributed_script_calls_delta", item.attributedScriptCallsDelta, actorUserId, batchId);
        long scriptErrors = checkDelta("attributed_script_errors_delta", item.attributedScriptErrorsDelta, actorUserId, batchId);
        return new ValidatedDeltas(invoke, resourceRead, materializeFail, scriptCalls, scriptErrors);
    }

    private long checkDelta(String field, Long value, String actorUserId, String batchId) {
        if (value == null) return 0L;
        if (value < 0 || value > MAX_DELTA_PER_ITEM) {
            LOGGER.warn("skill telemetry batch rejected: anomalous {}={}, actorUserId={}, batchId={}",
                    field, value, actorUserId, batchId);
            throw new BadRequestException(field + " must be between 0 and " + MAX_DELTA_PER_ITEM);
        }
        return value;
    }

    private void auditStatusChange(String actorUserId, String skillId, String papercutId,
                                   SkillPapercutStatus fromStatus, SkillPapercutStatus toStatus) {
        LOGGER.info("skill papercut status audit: actorUserId={}, skillId={}, papercutId={}, fromStatus={}, toStatus={}",
                actorUserId, skillId, papercutId, fromStatus, toStatus);
        ActionLogContext.put("audit_event", "skill_papercut_status_change");
        ActionLogContext.put("audit_actor_user_id", actorUserId);
        ActionLogContext.put("audit_skill_id", skillId);
        ActionLogContext.put("audit_papercut_id", papercutId);
        ActionLogContext.put("audit_from_status", fromStatus.name());
        ActionLogContext.put("audit_to_status", toStatus.name());
    }

    private record CollectedBatch(List<SkillTelemetryDelta> deltas, int droppedUnknown) {
    }

    private record ValidatedDeltas(long invoke, long resourceRead, long materializeFail,
                                   long attributedScriptCalls, long attributedScriptErrors) {
    }

    public record ReportPapercutCommand(
            SkillPapercutCategory category,
            String summary,
            String observedError,
            String suggestedFix,
            String packageDigest,
            String reporterUserId,
            String sessionId
    ) {
    }

    public record CleanupResult(int purgedBatches, int purgedPapercuts) {
    }
}
