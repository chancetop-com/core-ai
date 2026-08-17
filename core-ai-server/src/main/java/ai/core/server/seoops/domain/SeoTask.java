package ai.core.server.seoops.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * @author xander
 */
@Collection(name = "seo_tasks")
public class SeoTask {
    @Id
    public String id;

    @NotNull
    @Field(name = "merchant_id")
    public String merchantId;

    @Field(name = "location_id")
    public String locationId;

    @NotNull
    @Field(name = "status")
    public SeoTaskStatus status;

    @NotNull
    @Field(name = "evidence_state")
    public SeoEvidenceState evidenceState;

    @Field(name = "owner_id")
    public String ownerId;

    @Field(name = "due_at")
    public ZonedDateTime dueAt;

    @NotNull
    @Field(name = "priority_rank")
    public Integer priorityRank;

    @NotNull
    @Field(name = "task_revision")
    public Long taskRevision;

    @NotNull
    @Field(name = "state_version")
    public Long stateVersion;

    @NotNull
    @Field(name = "creation_idempotency_key")
    public String creationIdempotencyKey;

    @NotNull
    @Field(name = "creation_request_fingerprint")
    public String creationRequestFingerprint;

    @NotNull
    @Field(name = "current_revision")
    public TaskRevision currentRevision;

    @NotNull
    @Field(name = "revisions")
    public List<TaskRevision> revisions = new ArrayList<>();

    @NotNull
    @Field(name = "evidence_refs")
    public List<EvidenceRef> evidenceRefs = new ArrayList<>();

    @NotNull
    @Field(name = "approval_decisions")
    public List<ApprovalDecision> approvalDecisions = new ArrayList<>();

    @NotNull
    @Field(name = "conversation_links")
    public List<ConversationLink> conversationLinks = new ArrayList<>();

    @NotNull
    @Field(name = "agent_run_links")
    public List<AgentRunLink> agentRunLinks = new ArrayList<>();

    @NotNull
    @Field(name = "events")
    public List<TaskEvent> events = new ArrayList<>();

    @NotNull
    @Field(name = "created_by")
    public String createdBy;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;

    public static class TaskRevision {
        @NotNull
        @Field(name = "revision")
        public Long revision;

        @NotNull
        @Field(name = "title")
        public String title;

        @NotNull
        @Field(name = "task_type")
        public String taskType;

        @NotNull
        @Field(name = "source")
        public String source;

        @NotNull
        @Field(name = "priority")
        public String priority;

        @NotNull
        @Field(name = "impact")
        public String impact;

        @Field(name = "owner_id")
        public String ownerId;

        @Field(name = "due_at")
        public ZonedDateTime dueAt;

        @NotNull
        @Field(name = "execution_spec")
        public String executionSpec;

        @NotNull
        @Field(name = "execution_spec_hash")
        public String executionSpecHash;

        @NotNull
        @Field(name = "required_evidence_types")
        public List<String> requiredEvidenceTypes = new ArrayList<>();

        @NotNull
        @Field(name = "idempotency_key")
        public String idempotencyKey;

        @NotNull
        @Field(name = "request_fingerprint")
        public String requestFingerprint;

        @NotNull
        @Field(name = "created_by")
        public String createdBy;

        @NotNull
        @Field(name = "created_at")
        public ZonedDateTime createdAt;
    }

    public static class EvidenceRef {
        @NotNull
        @Field(name = "id")
        public String id;

        @NotNull
        @Field(name = "task_revision")
        public Long taskRevision;

        @NotNull
        @Field(name = "type")
        public String type;

        @Field(name = "artifact_id")
        public String artifactId;

        @Field(name = "file_id")
        public String fileId;

        @Field(name = "source_ref")
        public String sourceRef;

        @Field(name = "sha256")
        public String sha256;

        @NotNull
        @Field(name = "captured_at")
        public ZonedDateTime capturedAt;

        @NotNull
        @Field(name = "verification_status")
        public SeoEvidenceVerification verificationStatus;

        @NotNull
        @Field(name = "requirement_key")
        public String requirementKey;

        @NotNull
        @Field(name = "idempotency_key")
        public String idempotencyKey;

        @NotNull
        @Field(name = "request_fingerprint")
        public String requestFingerprint;

        @NotNull
        @Field(name = "created_by")
        public String createdBy;

        @NotNull
        @Field(name = "created_at")
        public ZonedDateTime createdAt;
    }

    public static class ApprovalDecision {
        @NotNull
        @Field(name = "id")
        public String id;

        @NotNull
        @Field(name = "decision")
        public SeoApprovalAction decision;

        @Field(name = "reason")
        public String reason;

        @NotNull
        @Field(name = "task_revision")
        public Long taskRevision;

        @NotNull
        @Field(name = "execution_spec_hash")
        public String executionSpecHash;

        @NotNull
        @Field(name = "expected_state_version")
        public Long expectedStateVersion;

        @NotNull
        @Field(name = "resulting_state_version")
        public Long resultingStateVersion;

        @NotNull
        @Field(name = "idempotency_key")
        public String idempotencyKey;

        @NotNull
        @Field(name = "request_fingerprint")
        public String requestFingerprint;

        @NotNull
        @Field(name = "actor_id")
        public String actorId;

        @NotNull
        @Field(name = "decided_at")
        public ZonedDateTime decidedAt;
    }

    public static class TaskEvent {
        @NotNull
        @Field(name = "id")
        public String id;

        @NotNull
        @Field(name = "type")
        public String type;

        @NotNull
        @Field(name = "actor_id")
        public String actorId;

        @Field(name = "from_status")
        public String fromStatus;

        @NotNull
        @Field(name = "to_status")
        public String toStatus;

        @NotNull
        @Field(name = "task_revision")
        public Long taskRevision;

        @NotNull
        @Field(name = "resulting_state_version")
        public Long resultingStateVersion;

        @Field(name = "reference_id")
        public String referenceId;

        @NotNull
        @Field(name = "occurred_at")
        public ZonedDateTime occurredAt;
    }

    public static class ConversationLink {
        @NotNull
        @Field(name = "conversation_id")
        public String conversationId;

        @NotNull
        @Field(name = "relationship")
        public String relationship;

        @NotNull
        @Field(name = "idempotency_key")
        public String idempotencyKey;

        @NotNull
        @Field(name = "request_fingerprint")
        public String requestFingerprint;

        @NotNull
        @Field(name = "linked_by")
        public String linkedBy;

        @NotNull
        @Field(name = "linked_at")
        public ZonedDateTime linkedAt;
    }

    public static class AgentRunLink {
        @NotNull
        @Field(name = "agent_run_id")
        public String agentRunId;

        @NotNull
        @Field(name = "relationship")
        public String relationship;

        @NotNull
        @Field(name = "linked_by")
        public String linkedBy;

        @NotNull
        @Field(name = "linked_at")
        public ZonedDateTime linkedAt;
    }
}
