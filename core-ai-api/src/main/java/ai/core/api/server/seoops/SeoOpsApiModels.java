package ai.core.api.server.seoops;

import core.framework.api.json.Property;
import core.framework.api.web.service.QueryParam;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * @author xander
 */
public final class SeoOpsApiModels {
    private SeoOpsApiModels() {
    }

    public static class PageRequest {
        @QueryParam(name = "offset")
        public Integer offset;
        @QueryParam(name = "limit")
        public Integer limit;
        @QueryParam(name = "merchant_id")
        public String merchantId;
        @QueryParam(name = "location_id")
        public String locationId;
        @QueryParam(name = "status")
        public String status;
        @QueryParam(name = "owner_id")
        public String ownerId;
        @QueryParam(name = "evidence_state")
        public String evidenceState;
        @QueryParam(name = "report_type")
        public String reportType;
        @QueryParam(name = "captured_from")
        public String capturedFrom;
        @QueryParam(name = "captured_to")
        public String capturedTo;
        @QueryParam(name = "freshness")
        public String freshness;
    }

    public static class CreateMerchantRequest {
        public String slug;
        @Property(name = "display_name")
        public String displayName;
        public List<String> tags;
        @Property(name = "operator_user_ids")
        public List<String> operatorUserIds;
        @Property(name = "idempotency_key")
        public String idempotencyKey;
    }

    public static class CreateLocationRequest {
        public String slug;
        @Property(name = "display_name")
        public String displayName;
        public String timezone;
        @Property(name = "external_identities")
        public Map<String, String> externalIdentities;
        @Property(name = "readiness_status")
        public String readinessStatus;
        @Property(name = "missing_requirements")
        public List<String> missingRequirements;
        @Property(name = "idempotency_key")
        public String idempotencyKey;
    }

    public static class TaskDefinitionRequest {
        public String title;
        @Property(name = "task_type")
        public String taskType;
        public String source;
        public String priority;
        public String impact;
        @Property(name = "owner_id")
        public String ownerId;
        @Property(name = "due_at")
        public String dueAt;
        @Property(name = "execution_spec")
        public String executionSpec;
        @Property(name = "required_evidence_types")
        public List<String> requiredEvidenceTypes;
        @Property(name = "conversation_id")
        public String conversationId;
    }

    public static class CreateTaskRequest {
        @Property(name = "merchant_id")
        public String merchantId;
        @Property(name = "location_id")
        public String locationId;
        public TaskDefinitionRequest definition;
        @Property(name = "idempotency_key")
        public String idempotencyKey;
    }

    public static class CreateRevisionRequest {
        public TaskDefinitionRequest definition;
        @Property(name = "expected_state_version")
        public Long expectedStateVersion;
        @Property(name = "idempotency_key")
        public String idempotencyKey;
    }

    public static class AppendEvidenceRequest {
        public String type;
        @Property(name = "artifact_id")
        public String artifactId;
        @Property(name = "file_id")
        public String fileId;
        @Property(name = "source_ref")
        public String sourceRef;
        public String sha256;
        @Property(name = "captured_at")
        public String capturedAt;
        @Property(name = "verification_status")
        public String verificationStatus;
        @Property(name = "requirement_key")
        public String requirementKey;
        @Property(name = "expected_state_version")
        public Long expectedStateVersion;
        @Property(name = "idempotency_key")
        public String idempotencyKey;
    }

    public static class LinkConversationRequest {
        @Property(name = "conversation_id")
        public String conversationId;
        @Property(name = "expected_state_version")
        public Long expectedStateVersion;
        @Property(name = "idempotency_key")
        public String idempotencyKey;
    }

    public static class ApprovalPreviewRequest {
        @Property(name = "task_revision")
        public Long taskRevision;
        @Property(name = "expected_state_version")
        public Long expectedStateVersion;
    }

    public static class ApprovalDecisionRequest {
        public String decision;
        public String reason;
        @Property(name = "task_revision")
        public Long taskRevision;
        @Property(name = "execution_spec_hash")
        public String executionSpecHash;
        @Property(name = "expected_state_version")
        public Long expectedStateVersion;
        @Property(name = "idempotency_key")
        public String idempotencyKey;
    }

    public static class RuntimeConfigView {
        @NotNull
        @Property(name = "copilot_enabled")
        public Boolean copilotEnabled;
        @Property(name = "copilot_agent_id")
        public String copilotAgentId;
    }

    public static class IdNameView {
        @NotNull
        @Property(name = "id")
        public String id;
        @NotNull
        @Property(name = "name")
        public String name;
    }

    public static class LocationSummaryView {
        @NotNull
        @Property(name = "id")
        public String id;
        @NotNull
        @Property(name = "display_name")
        public String displayName;
        @NotNull
        @Property(name = "readiness_status")
        public String readinessStatus;
    }

    public static class MerchantSummaryView {
        @NotNull
        @Property(name = "id")
        public String id;
        @NotNull
        @Property(name = "slug")
        public String slug;
        @NotNull
        @Property(name = "display_name")
        public String displayName;
        @NotNull
        @Property(name = "operator_user_ids")
        public List<String> operatorUserIds;
        @NotNull
        @Property(name = "operators")
        public List<IdNameView> operators;
        @NotNull
        @Property(name = "owner_ids")
        public List<String> ownerIds;
        @NotNull
        @Property(name = "locations")
        public List<LocationSummaryView> locations;
        @NotNull
        @Property(name = "location_count")
        public Integer locationCount;
        @NotNull
        @Property(name = "task_count")
        public Long taskCount;
        @NotNull
        @Property(name = "ready_for_approval_count")
        public Long readyForApprovalCount;
        @NotNull
        @Property(name = "blocked_count")
        public Long blockedCount;
        @NotNull
        @Property(name = "overdue_count")
        public Long overdueCount;
        @NotNull
        @Property(name = "health")
        public String health;
    }

    public static class PortfolioTotalsView {
        @NotNull
        @Property(name = "tasks")
        public Long tasks;
        @NotNull
        @Property(name = "blocked")
        public Long blocked;
        @NotNull
        @Property(name = "ready_for_approval")
        public Long readyForApproval;
        @NotNull
        @Property(name = "overdue")
        public Long overdue;
    }

    public static class PortfolioView {
        @NotNull
        @Property(name = "merchants")
        public List<MerchantSummaryView> merchants;
        @NotNull
        @Property(name = "totals")
        public PortfolioTotalsView totals;
    }

    public static class MerchantView {
        @NotNull
        @Property(name = "id")
        public String id;
        @NotNull
        @Property(name = "slug")
        public String slug;
        @NotNull
        @Property(name = "display_name")
        public String displayName;
        @NotNull
        @Property(name = "tags")
        public List<String> tags;
        @NotNull
        @Property(name = "operator_user_ids")
        public List<String> operatorUserIds;
        @NotNull
        @Property(name = "created_at")
        public ZonedDateTime createdAt;
        @NotNull
        @Property(name = "updated_at")
        public ZonedDateTime updatedAt;
    }

    public static class LocationView {
        @NotNull
        @Property(name = "id")
        public String id;
        @NotNull
        @Property(name = "merchant_id")
        public String merchantId;
        @NotNull
        @Property(name = "slug")
        public String slug;
        @NotNull
        @Property(name = "display_name")
        public String displayName;
        @NotNull
        @Property(name = "timezone")
        public String timezone;
        @NotNull
        @Property(name = "external_identities")
        public Map<String, String> externalIdentities;
        @NotNull
        @Property(name = "readiness_status")
        public String readinessStatus;
        @NotNull
        @Property(name = "missing_requirements")
        public List<String> missingRequirements;
        @NotNull
        @Property(name = "created_at")
        public ZonedDateTime createdAt;
        @NotNull
        @Property(name = "updated_at")
        public ZonedDateTime updatedAt;
    }

    public static class TaskSummaryView {
        @NotNull
        @Property(name = "id")
        public String id;
        @NotNull
        @Property(name = "merchant_id")
        public String merchantId;
        @NotNull
        @Property(name = "merchant_name")
        public String merchantName;
        @Property(name = "location_id")
        public String locationId;
        @Property(name = "location_name")
        public String locationName;
        @NotNull
        @Property(name = "title")
        public String title;
        @NotNull
        @Property(name = "task_type")
        public String taskType;
        @NotNull
        @Property(name = "priority")
        public String priority;
        @NotNull
        @Property(name = "impact")
        public String impact;
        @Property(name = "owner_id")
        public String ownerId;
        @Property(name = "due_at")
        public ZonedDateTime dueAt;
        @NotNull
        @Property(name = "status")
        public String status;
        @NotNull
        @Property(name = "evidence_state")
        public String evidenceState;
        @NotNull
        @Property(name = "task_revision")
        public Long taskRevision;
        @NotNull
        @Property(name = "state_version")
        public Long stateVersion;
        @NotNull
        @Property(name = "updated_at")
        public ZonedDateTime updatedAt;
    }

    public static class EvidenceRefView {
        @NotNull
        @Property(name = "id")
        public String id;
        @NotNull
        @Property(name = "task_revision")
        public Long taskRevision;
        @NotNull
        @Property(name = "type")
        public String type;
        @Property(name = "artifact_id")
        public String artifactId;
        @Property(name = "file_id")
        public String fileId;
        @Property(name = "source_ref")
        public String sourceRef;
        @Property(name = "sha256")
        public String sha256;
        @NotNull
        @Property(name = "captured_at")
        public ZonedDateTime capturedAt;
        @NotNull
        @Property(name = "verification_status")
        public String verificationStatus;
        @NotNull
        @Property(name = "requirement_key")
        public String requirementKey;
        @NotNull
        @Property(name = "created_by")
        public String createdBy;
        @NotNull
        @Property(name = "created_at")
        public ZonedDateTime createdAt;
    }

    public static class ApprovalDecisionView {
        @NotNull
        @Property(name = "id")
        public String id;
        @NotNull
        @Property(name = "decision")
        public String decision;
        @Property(name = "reason")
        public String reason;
        @NotNull
        @Property(name = "task_revision")
        public Long taskRevision;
        @NotNull
        @Property(name = "execution_spec_hash")
        public String executionSpecHash;
        @NotNull
        @Property(name = "expected_state_version")
        public Long expectedStateVersion;
        @NotNull
        @Property(name = "resulting_state_version")
        public Long resultingStateVersion;
        @NotNull
        @Property(name = "actor_id")
        public String actorId;
        @NotNull
        @Property(name = "decided_at")
        public ZonedDateTime decidedAt;
    }

    public static class ConversationLinkView {
        @NotNull
        @Property(name = "conversation_id")
        public String conversationId;
        @NotNull
        @Property(name = "relationship")
        public String relationship;
        @NotNull
        @Property(name = "linked_by")
        public String linkedBy;
        @NotNull
        @Property(name = "linked_at")
        public ZonedDateTime linkedAt;
    }

    public static class AgentRunLinkView {
        @NotNull
        @Property(name = "agent_run_id")
        public String agentRunId;
        @NotNull
        @Property(name = "relationship")
        public String relationship;
        @Property(name = "status")
        public String status;
        @NotNull
        @Property(name = "linked_by")
        public String linkedBy;
        @NotNull
        @Property(name = "linked_at")
        public ZonedDateTime linkedAt;
    }

    public static class TaskEventView {
        @NotNull
        @Property(name = "id")
        public String id;
        @NotNull
        @Property(name = "type")
        public String type;
        @NotNull
        @Property(name = "actor_id")
        public String actorId;
        @Property(name = "from_status")
        public String fromStatus;
        @Property(name = "to_status")
        public String toStatus;
        @NotNull
        @Property(name = "task_revision")
        public Long taskRevision;
        @NotNull
        @Property(name = "resulting_state_version")
        public Long resultingStateVersion;
        @Property(name = "reference_id")
        public String referenceId;
        @NotNull
        @Property(name = "occurred_at")
        public ZonedDateTime occurredAt;
    }

    public static class SeoTaskView {
        @NotNull
        @Property(name = "id")
        public String id;
        @NotNull
        @Property(name = "merchant_id")
        public String merchantId;
        @NotNull
        @Property(name = "merchant_name")
        public String merchantName;
        @Property(name = "location_id")
        public String locationId;
        @Property(name = "location_name")
        public String locationName;
        @NotNull
        @Property(name = "title")
        public String title;
        @NotNull
        @Property(name = "task_type")
        public String taskType;
        @NotNull
        @Property(name = "source")
        public String source;
        @NotNull
        @Property(name = "priority")
        public String priority;
        @NotNull
        @Property(name = "impact")
        public String impact;
        @Property(name = "owner_id")
        public String ownerId;
        @Property(name = "due_at")
        public ZonedDateTime dueAt;
        @NotNull
        @Property(name = "status")
        public String status;
        @NotNull
        @Property(name = "evidence_state")
        public String evidenceState;
        @NotNull
        @Property(name = "task_revision")
        public Long taskRevision;
        @NotNull
        @Property(name = "state_version")
        public Long stateVersion;
        @NotNull
        @Property(name = "execution_spec")
        public String executionSpec;
        @NotNull
        @Property(name = "execution_spec_hash")
        public String executionSpecHash;
        @NotNull
        @Property(name = "required_evidence_types")
        public List<String> requiredEvidenceTypes;
        @NotNull
        @Property(name = "evidence_refs")
        public List<EvidenceRefView> evidenceRefs;
        @NotNull
        @Property(name = "approval_decisions")
        public List<ApprovalDecisionView> approvalDecisions;
        @NotNull
        @Property(name = "conversation_links")
        public List<ConversationLinkView> conversationLinks;
        @NotNull
        @Property(name = "agent_run_links")
        public List<AgentRunLinkView> agentRunLinks;
        @NotNull
        @Property(name = "created_at")
        public ZonedDateTime createdAt;
        @NotNull
        @Property(name = "updated_at")
        public ZonedDateTime updatedAt;
    }

    public static class ApprovalPreviewView {
        @NotNull
        @Property(name = "reviewable")
        public Boolean reviewable;
        @NotNull
        @Property(name = "blockers")
        public List<String> blockers;
        @NotNull
        @Property(name = "task_revision")
        public Long taskRevision;
        @NotNull
        @Property(name = "state_version")
        public Long stateVersion;
        @NotNull
        @Property(name = "execution_spec_hash")
        public String executionSpecHash;
        @NotNull
        @Property(name = "evidence_state")
        public String evidenceState;
        @NotNull
        @Property(name = "current_status")
        public String currentStatus;
    }

    public static class ReviewItemView {
        @NotNull
        @Property(name = "task_id")
        public String taskId;
        @NotNull
        @Property(name = "merchant_id")
        public String merchantId;
        @Property(name = "location_id")
        public String locationId;
        @NotNull
        @Property(name = "classification")
        public String classification;
        @Property(name = "goal")
        public String goal;
        @Property(name = "baseline")
        public String baseline;
        @Property(name = "action")
        public String action;
        @Property(name = "observed_change")
        public String observedChange;
        @NotNull
        @Property(name = "competing_explanations")
        public List<String> competingExplanations;
        @NotNull
        @Property(name = "conclusion_strength")
        public String conclusionStrength;
        @Property(name = "follow_up_test")
        public String followUpTest;
        @NotNull
        @Property(name = "evidence_ids")
        public List<String> evidenceIds;
        @NotNull
        @Property(name = "updated_at")
        public ZonedDateTime updatedAt;
    }

    public static class ReportItemView {
        @NotNull
        @Property(name = "task_id")
        public String taskId;
        @NotNull
        @Property(name = "merchant_id")
        public String merchantId;
        @Property(name = "location_id")
        public String locationId;
        @NotNull
        @Property(name = "evidence_id")
        public String evidenceId;
        @NotNull
        @Property(name = "report_type")
        public String reportType;
        @Property(name = "artifact_id")
        public String artifactId;
        @Property(name = "file_id")
        public String fileId;
        @Property(name = "source_ref")
        public String sourceRef;
        @Property(name = "sha256")
        public String sha256;
        @NotNull
        @Property(name = "captured_at")
        public ZonedDateTime capturedAt;
        @NotNull
        @Property(name = "freshness")
        public String freshness;
    }

    public static class ListTasksResponse {
        @NotNull
        @Property(name = "items")
        public List<TaskSummaryView> items;
        @NotNull
        @Property(name = "offset")
        public Integer offset;
        @NotNull
        @Property(name = "limit")
        public Integer limit;
        @NotNull
        @Property(name = "total")
        public Long total;
    }

    public static class ListReviewsResponse {
        @NotNull
        @Property(name = "items")
        public List<ReviewItemView> items;
        @NotNull
        @Property(name = "offset")
        public Integer offset;
        @NotNull
        @Property(name = "limit")
        public Integer limit;
        @NotNull
        @Property(name = "total")
        public Long total;
    }

    public static class ListReportsResponse {
        @NotNull
        @Property(name = "items")
        public List<ReportItemView> items;
        @NotNull
        @Property(name = "offset")
        public Integer offset;
        @NotNull
        @Property(name = "limit")
        public Integer limit;
        @NotNull
        @Property(name = "total")
        public Long total;
    }

    public static class ListEventsResponse {
        @NotNull
        @Property(name = "items")
        public List<TaskEventView> items;
        @NotNull
        @Property(name = "offset")
        public Integer offset;
        @NotNull
        @Property(name = "limit")
        public Integer limit;
        @NotNull
        @Property(name = "total")
        public Long total;
    }

}
