package ai.core.server.seoops;

import ai.core.api.server.seoops.SeoOpsApiModels.AppendEvidenceRequest;
import ai.core.api.server.seoops.SeoOpsApiModels.CreateRevisionRequest;
import ai.core.api.server.seoops.SeoOpsApiModels.CreateTaskRequest;
import ai.core.api.server.seoops.SeoOpsApiModels.TaskDefinitionRequest;
import ai.core.server.seoops.domain.SeoEvidenceVerification;
import ai.core.server.seoops.domain.SeoLocation;
import ai.core.server.seoops.domain.SeoMerchant;
import ai.core.server.seoops.domain.SeoTask;
import ai.core.server.seoops.domain.SeoTaskStatus;
import ai.core.server.web.auth.RequestAuthenticator;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.NotFoundException;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * @author xander
 */
public class SeoTaskCommandService {
    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "URGENT");
    private static final Set<String> IMPACTS = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<SeoTaskStatus> REVISION_STATES = Set.of(
        SeoTaskStatus.DRAFT, SeoTaskStatus.NEEDS_INPUT, SeoTaskStatus.BLOCKED,
        SeoTaskStatus.READY_FOR_APPROVAL, SeoTaskStatus.REVISION_REQUIRED, SeoTaskStatus.APPROVAL_REVOKED);
    private static final Set<SeoTaskStatus> EVIDENCE_STATES = Set.of(
        SeoTaskStatus.DRAFT, SeoTaskStatus.NEEDS_INPUT, SeoTaskStatus.BLOCKED, SeoTaskStatus.READY_FOR_APPROVAL);
    private static final int MAX_TITLE_CODE_POINTS = 200;
    private static final int MAX_EXECUTION_SPEC_BYTES = 32 * 1024;
    private static final int MAX_REQUIRED_EVIDENCE = 50;
    private static final int MAX_SOURCE_REF_CODE_POINTS = 1024;
    private static final int MAX_REVISIONS = 50;
    private static final int MAX_EVIDENCE = 200;
    private static final int MAX_EVENTS = 1000;

    @Inject
    MongoCollection<SeoTask> taskCollection;

    @Inject
    SeoMerchantService merchantService;

    @Inject
    SeoExecutionSpecHasher hasher;

    @Inject
    SeoTaskPolicy taskPolicy;

    @Inject
    SeoConversationPolicy conversationPolicy;

    public SeoTask createTask(String actorUserId, CreateTaskRequest request) {
        if (request == null) throw new BadRequestException("request is required");
        var merchant = merchantService.requireVisibleMerchant(actorUserId, request.merchantId);
        var location = resolveLocation(actorUserId, merchant.id, request.locationId);
        var idempotencyKey = requireText(request.idempotencyKey, "idempotency_key");
        var definition = buildRevision(actorUserId, merchant, request.definition, 1L, idempotencyKey);
        var fingerprint = taskFingerprint(merchant.id, location == null ? null : location.id, definition);
        var replay = findByCreationIdempotency(merchant.id, idempotencyKey);
        if (replay != null) return requireCreationReplay(replay, fingerprint);

        var now = ZonedDateTime.now();
        var task = new SeoTask();
        task.id = UUID.randomUUID().toString();
        task.merchantId = merchant.id;
        task.locationId = location == null ? null : location.id;
        task.ownerId = definition.ownerId;
        task.dueAt = definition.dueAt;
        task.taskRevision = 1L;
        task.stateVersion = 1L;
        task.creationIdempotencyKey = idempotencyKey;
        task.creationRequestFingerprint = fingerprint;
        task.currentRevision = definition;
        task.revisions.add(definition);
        appendOriginatingConversation(task, actorUserId, request.definition.conversationId, idempotencyKey, now);
        var readiness = taskPolicy.evaluate(task, location, 1L);
        task.status = readiness.status();
        task.evidenceState = readiness.evidenceState();
        task.events.add(creationEvent(actorUserId, task, now));
        task.createdBy = actorUserId;
        task.createdAt = now;
        task.updatedAt = now;
        try {
            taskCollection.insert(task);
            return task;
        } catch (RuntimeException e) {
            var winner = findByCreationIdempotency(merchant.id, idempotencyKey);
            if (winner != null && Objects.equals(winner.creationRequestFingerprint, fingerprint)) return winner;
            if (winner != null) {
                throw new ConflictException("idempotency key was already used with different content",
                    "IDEMPOTENCY_KEY_REUSED", e);
            }
            throw e;
        }
    }

    public SeoTask createRevision(String actorUserId, String taskId, CreateRevisionRequest request) {
        if (request == null) throw new BadRequestException("request is required");
        var task = requireVisibleTask(actorUserId, taskId);
        var key = requireText(request.idempotencyKey, "idempotency_key");
        var existing = findRevisionByKey(task, key);
        if (existing != null) return requireRevisionReplay(task, existing, request);
        requireExpectedState(task, request.expectedStateVersion);
        if (!REVISION_STATES.contains(task.status)) {
            throw conflict("task status does not allow revision");
        }
        requireCapacity(task.revisions, MAX_REVISIONS);
        requireEventCapacity(task);
        var merchant = merchantService.requireVisibleMerchant(actorUserId, task.merchantId);
        var location = resolveLocation(actorUserId, task.merchantId, task.locationId);
        var nextRevision = task.taskRevision + 1;
        var revision = buildRevision(actorUserId, merchant, request.definition, nextRevision, key);
        var fingerprint = revision.requestFingerprint;
        var previousRevision = task.currentRevision;
        task.currentRevision = revision;
        var readiness = taskPolicy.evaluate(task, location, nextRevision);
        task.currentRevision = previousRevision;
        var now = ZonedDateTime.now();
        var event = revisionEvent(actorUserId, task, readiness.status(), now);
        var link = originatingConversation(actorUserId, request.definition.conversationId, key, now);
        var updated = taskCollection.update(Filters.and(
            Filters.eq("_id", task.id), Filters.eq("task_revision", task.taskRevision),
            Filters.eq("state_version", task.stateVersion), Filters.ne("revisions.idempotency_key", key)),
            revisionUpdates(revision, event, link, readiness, now));
        if (updated == 0) return resolveRevisionConflict(actorUserId, task.id, key, fingerprint);
        applyRevision(task, revision, event, link, readiness, now);
        return task;
    }

    public SeoTask appendEvidence(String actorUserId, String taskId, AppendEvidenceRequest request) {
        if (request == null) throw new BadRequestException("request is required");
        var task = requireVisibleTask(actorUserId, taskId);
        requireExpectedState(task, request.expectedStateVersion);
        var evidence = buildEvidence(actorUserId, task, request);
        for (int attempt = 0; attempt < 3; attempt++) {
            var replay = findEvidenceByKey(task, evidence.idempotencyKey);
            if (replay != null) return requireEvidenceReplay(task, replay, evidence.requestFingerprint);
            if (!EVIDENCE_STATES.contains(task.status)) throw conflict("task status does not allow evidence");
            requireCapacity(task.evidenceRefs, MAX_EVIDENCE);
            requireEventCapacity(task);
            var location = resolveLocation(actorUserId, task.merchantId, task.locationId);
            var candidateEvidence = new ArrayList<>(task.evidenceRefs);
            candidateEvidence.add(evidence);
            var originalEvidence = task.evidenceRefs;
            task.evidenceRefs = candidateEvidence;
            var readiness = taskPolicy.evaluate(task, location, task.taskRevision);
            task.evidenceRefs = originalEvidence;
            var now = ZonedDateTime.now();
            var event = evidenceEvent(actorUserId, task, readiness.status(), evidence.id, now);
            var updated = taskCollection.update(Filters.and(
                Filters.eq("_id", task.id), Filters.eq("task_revision", task.taskRevision),
                Filters.eq("state_version", task.stateVersion),
                Filters.ne("evidence_refs.idempotency_key", evidence.idempotencyKey)),
                Updates.combine(
                    Updates.push("evidence_refs", evidence), Updates.push("events", event),
                    Updates.set("status", readiness.status()), Updates.set("evidence_state", readiness.evidenceState()),
                    Updates.set("updated_at", now), Updates.inc("state_version", 1L)));
            if (updated > 0) {
                applyEvidence(task, evidence, event, readiness, now);
                return task;
            }
            task = requireVisibleTask(actorUserId, taskId);
            if (!Objects.equals(task.taskRevision, evidence.taskRevision)) throw conflict("task revision changed");
        }
        throw conflict("task changed concurrently");
    }

    SeoTask requireVisibleTask(String actorUserId, String taskId) {
        var task = taskCollection.get(taskId).orElse(null);
        if (task == null) throw new NotFoundException("task not found");
        merchantService.requireVisibleMerchant(actorUserId, task.merchantId);
        return task;
    }

    private void applyEvidence(SeoTask task, SeoTask.EvidenceRef evidence, SeoTask.TaskEvent event,
                               SeoTaskPolicy.Readiness readiness, ZonedDateTime now) {
        task.evidenceRefs.add(evidence);
        task.events.add(event);
        task.stateVersion++;
        task.status = readiness.status();
        task.evidenceState = readiness.evidenceState();
        task.updatedAt = now;
    }

    private org.bson.conversions.Bson revisionUpdates(SeoTask.TaskRevision revision, SeoTask.TaskEvent event,
                                                      SeoTask.ConversationLink link, SeoTaskPolicy.Readiness readiness,
                                                      ZonedDateTime now) {
        var updates = new ArrayList<org.bson.conversions.Bson>();
        updates.add(Updates.push("revisions", revision));
        updates.add(Updates.push("events", event));
        if (link != null) updates.add(Updates.push("conversation_links", link));
        updates.add(Updates.set("current_revision", revision));
        updates.add(Updates.set("owner_id", revision.ownerId));
        updates.add(Updates.set("due_at", revision.dueAt));
        updates.add(Updates.set("status", readiness.status()));
        updates.add(Updates.set("evidence_state", readiness.evidenceState()));
        updates.add(Updates.set("updated_at", now));
        updates.add(Updates.inc("task_revision", 1L));
        updates.add(Updates.inc("state_version", 1L));
        return Updates.combine(updates);
    }

    private void applyRevision(SeoTask task, SeoTask.TaskRevision revision, SeoTask.TaskEvent event,
                               SeoTask.ConversationLink link, SeoTaskPolicy.Readiness readiness, ZonedDateTime now) {
        task.revisions.add(revision);
        if (link != null) task.conversationLinks.add(link);
        task.events.add(event);
        task.currentRevision = revision;
        task.ownerId = revision.ownerId;
        task.dueAt = revision.dueAt;
        task.taskRevision++;
        task.stateVersion++;
        task.status = readiness.status();
        task.evidenceState = readiness.evidenceState();
        task.updatedAt = now;
    }

    private SeoTask.TaskRevision buildRevision(String actorUserId, SeoMerchant merchant,
                                               TaskDefinitionRequest request, Long revision, String idempotencyKey) {
        if (request == null) throw new BadRequestException("definition is required");
        var title = requireText(request.title, "title");
        requireCodePointLimit(title, MAX_TITLE_CODE_POINTS, "title");
        var taskType = requireText(request.taskType, "task_type");
        var source = requireText(request.source, "source");
        var priority = requireEnum(request.priority, PRIORITIES, "priority");
        var impact = requireEnum(request.impact, IMPACTS, "impact");
        var owner = optionalText(request.ownerId);
        if (owner != null && !merchant.operatorUserIds.contains(owner)) {
            throw new BadRequestException("owner_id must be an active merchant operator");
        }
        var dueAt = parseOptionalTime(request.dueAt, "due_at");
        var rawSpec = requireText(request.executionSpec, "execution_spec");
        if (rawSpec.getBytes(StandardCharsets.UTF_8).length > MAX_EXECUTION_SPEC_BYTES) {
            throw new BadRequestException("execution_spec exceeds 32 KiB");
        }
        var canonicalSpec = hasher.canonicalize(rawSpec);
        var requiredEvidence = cleanRequirements(request.requiredEvidenceTypes);
        var item = new SeoTask.TaskRevision();
        item.revision = revision;
        item.title = title;
        item.taskType = taskType;
        item.source = source;
        item.priority = priority;
        item.impact = impact;
        item.ownerId = owner;
        item.dueAt = dueAt;
        item.executionSpec = canonicalSpec;
        item.executionSpecHash = hasher.hash(canonicalSpec);
        item.requiredEvidenceTypes = requiredEvidence;
        item.idempotencyKey = idempotencyKey;
        item.requestFingerprint = revisionFingerprint(item);
        item.createdBy = actorUserId;
        item.createdAt = ZonedDateTime.now();
        return item;
    }

    private SeoTask.EvidenceRef buildEvidence(String actorUserId, SeoTask task, AppendEvidenceRequest request) {
        var key = requireText(request.idempotencyKey, "idempotency_key");
        var type = requireText(request.type, "type");
        var requirementKey = requireText(request.requirementKey, "requirement_key");
        var sources = Stream.of(request.artifactId, request.fileId, request.sourceRef)
            .filter(value -> value != null && !value.isBlank()).count();
        if (sources != 1) throw new BadRequestException("exactly one evidence source is required");
        var sourceRef = optionalText(request.sourceRef);
        if (sourceRef != null) requireCodePointLimit(sourceRef, MAX_SOURCE_REF_CODE_POINTS, "source_ref");
        var sha256 = optionalText(request.sha256);
        if ((request.artifactId != null || request.fileId != null)
            && (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}"))) {
            throw new BadRequestException("byte-backed evidence requires a SHA-256 hex digest");
        }
        var evidence = new SeoTask.EvidenceRef();
        evidence.id = UUID.randomUUID().toString();
        evidence.taskRevision = task.taskRevision;
        evidence.type = type;
        evidence.artifactId = optionalText(request.artifactId);
        evidence.fileId = optionalText(request.fileId);
        evidence.sourceRef = sourceRef;
        evidence.sha256 = sha256 == null ? null : sha256.toLowerCase(Locale.ROOT);
        evidence.capturedAt = parseRequiredTime(request.capturedAt, "captured_at");
        evidence.verificationStatus = parseVerification(request.verificationStatus);
        evidence.requirementKey = requirementKey;
        evidence.idempotencyKey = key;
        evidence.requestFingerprint = evidenceFingerprint(evidence);
        evidence.createdBy = actorUserId;
        evidence.createdAt = ZonedDateTime.now();
        return evidence;
    }

    private void appendOriginatingConversation(SeoTask task, String actorUserId, String conversationId,
                                               String idempotencyKey, ZonedDateTime now) {
        var link = originatingConversation(actorUserId, conversationId, idempotencyKey, now);
        if (link != null) task.conversationLinks.add(link);
    }

    private SeoTask.ConversationLink originatingConversation(String actorUserId, String conversationId,
                                                              String idempotencyKey, ZonedDateTime now) {
        var session = conversationPolicy.requireOwnedChatSession(actorUserId, conversationId);
        if (session == null) return null;
        var link = new SeoTask.ConversationLink();
        link.conversationId = session.id;
        link.relationship = "ORIGINATING_DRAFT";
        link.idempotencyKey = idempotencyKey;
        link.requestFingerprint = fingerprint(session.id, link.relationship);
        link.linkedBy = actorUserId;
        link.linkedAt = now;
        return link;
    }

    private SeoTask.TaskEvent creationEvent(String actorId, SeoTask task, ZonedDateTime now) {
        return event("TASK_CREATED", actorId, null, task.status, task.taskRevision, task.stateVersion, null, now);
    }

    private SeoTask.TaskEvent revisionEvent(String actorId, SeoTask task, SeoTaskStatus to, ZonedDateTime now) {
        return event("TASK_REVISED", actorId, task.status, to, task.taskRevision + 1, task.stateVersion + 1, null, now);
    }

    private SeoTask.TaskEvent evidenceEvent(String actorId, SeoTask task, SeoTaskStatus to,
                                            String evidenceId, ZonedDateTime now) {
        return event("EVIDENCE_APPENDED", actorId, task.status, to, task.taskRevision,
            task.stateVersion + 1, evidenceId, now);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private SeoTask.TaskEvent event(String type, String actorId, SeoTaskStatus from, SeoTaskStatus to,
                                    Long revision, Long resultingVersion, String referenceId, ZonedDateTime now) {
        var event = new SeoTask.TaskEvent();
        event.id = UUID.randomUUID().toString();
        event.type = type;
        event.actorId = actorId;
        event.fromStatus = from == null ? null : from.name();
        event.toStatus = to.name();
        event.taskRevision = revision;
        event.resultingStateVersion = resultingVersion;
        event.referenceId = referenceId;
        event.occurredAt = now;
        return event;
    }

    private SeoLocation resolveLocation(String actorUserId, String merchantId, String locationId) {
        return locationId == null || locationId.isBlank()
            ? null
            : merchantService.requireVisibleLocation(actorUserId, merchantId, locationId);
    }

    private SeoTask findByCreationIdempotency(String merchantId, String key) {
        var matches = taskCollection.find(Filters.and(
            Filters.eq("merchant_id", merchantId), Filters.eq("creation_idempotency_key", key)));
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private SeoTask requireCreationReplay(SeoTask task, String fingerprint) {
        if (!Objects.equals(task.creationRequestFingerprint, fingerprint)) {
            throw conflict("idempotency key was already used with different content");
        }
        return task;
    }

    private SeoTask.TaskRevision findRevisionByKey(SeoTask task, String key) {
        return task.revisions.stream().filter(revision -> key.equals(revision.idempotencyKey)).findFirst().orElse(null);
    }

    private SeoTask.EvidenceRef findEvidenceByKey(SeoTask task, String key) {
        return task.evidenceRefs.stream().filter(evidence -> key.equals(evidence.idempotencyKey)).findFirst().orElse(null);
    }

    private SeoTask requireRevisionReplay(SeoTask task, SeoTask.TaskRevision existing, CreateRevisionRequest request) {
        var merchant = merchantService.requireVisibleMerchant(existing.createdBy, task.merchantId);
        var candidate = buildRevision(existing.createdBy, merchant, request.definition, existing.revision, existing.idempotencyKey);
        if (!Objects.equals(existing.requestFingerprint, candidate.requestFingerprint)) {
            throw conflict("idempotency key was already used with different content");
        }
        return task;
    }

    private SeoTask requireEvidenceReplay(SeoTask task, SeoTask.EvidenceRef existing, String fingerprint) {
        if (!Objects.equals(existing.requestFingerprint, fingerprint)) {
            throw conflict("idempotency key was already used with different content");
        }
        return task;
    }

    private SeoTask resolveRevisionConflict(String actorUserId, String taskId, String key, String fingerprint) {
        var latest = requireVisibleTask(actorUserId, taskId);
        var replay = findRevisionByKey(latest, key);
        if (replay != null && Objects.equals(replay.requestFingerprint, fingerprint)) return latest;
        throw conflict("task changed concurrently");
    }

    private void requireExpectedState(SeoTask task, Long expectedStateVersion) {
        if (expectedStateVersion == null || !expectedStateVersion.equals(task.stateVersion)) {
            throw conflict("state_version is stale");
        }
    }

    private void requireCapacity(List<?> items, int maximum) {
        if (items != null && items.size() >= maximum) {
            throw new ConflictException("task aggregate limit reached", "TASK_AGGREGATE_LIMIT_REACHED");
        }
    }

    private void requireEventCapacity(SeoTask task) {
        requireCapacity(task.events, MAX_EVENTS);
    }

    private List<String> cleanRequirements(List<String> values) {
        var clean = new LinkedHashSet<String>();
        if (values != null) {
            for (var value : values) clean.add(requireText(value, "required_evidence_types"));
        }
        if (clean.size() > MAX_REQUIRED_EVIDENCE) {
            throw new BadRequestException("required_evidence_types exceeds 50 entries");
        }
        return new ArrayList<>(clean);
    }

    private SeoEvidenceVerification parseVerification(String value) {
        try {
            return SeoEvidenceVerification.valueOf(requireText(value, "verification_status").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("verification_status is invalid", "INVALID_EVIDENCE_VERIFICATION", e);
        }
    }

    private ZonedDateTime parseOptionalTime(String value, String field) {
        return value == null || value.isBlank() ? null : parseRequiredTime(value, field);
    }

    private ZonedDateTime parseRequiredTime(String value, String field) {
        try {
            return ZonedDateTime.parse(requireText(value, field));
        } catch (DateTimeParseException e) {
            throw new BadRequestException(field + " must be an ISO-8601 date-time", "INVALID_DATE_TIME", e);
        }
    }

    private String taskFingerprint(String merchantId, String locationId, SeoTask.TaskRevision revision) {
        return fingerprint(merchantId, locationId, revision.requestFingerprint);
    }

    private String revisionFingerprint(SeoTask.TaskRevision revision) {
        return fingerprint(revision.title, revision.taskType, revision.source, revision.priority, revision.impact,
            revision.ownerId, revision.dueAt == null ? null : revision.dueAt.toString(), revision.executionSpec,
            String.join("\u001f", revision.requiredEvidenceTypes));
    }

    private String evidenceFingerprint(SeoTask.EvidenceRef evidence) {
        return fingerprint(evidence.type, evidence.artifactId, evidence.fileId, evidence.sourceRef, evidence.sha256,
            evidence.capturedAt.toString(), evidence.verificationStatus.name(), evidence.requirementKey,
            String.valueOf(evidence.taskRevision));
    }

    private String fingerprint(String... values) {
        var encoded = new StringBuilder();
        for (var value : values) {
            var safe = value == null ? "" : value;
            encoded.append(safe.length()).append(':').append(safe).append('|');
        }
        return "sha256:" + RequestAuthenticator.sha256(encoded.toString());
    }

    private String requireEnum(String value, Set<String> allowed, String field) {
        var normalized = requireText(value, field).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new BadRequestException(field + " is invalid");
        return normalized;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new BadRequestException(field + " is required");
        return value.trim();
    }

    private String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void requireCodePointLimit(String value, int maximum, String field) {
        if (value.codePointCount(0, value.length()) > maximum) {
            throw new BadRequestException(field + " exceeds " + maximum + " Unicode code points");
        }
    }

    private ConflictException conflict(String message) {
        return new ConflictException(message, "SEOOPS_CONFLICT");
    }
}
