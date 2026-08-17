package ai.core.server.seoops;

import ai.core.api.server.seoops.SeoOpsApiModels.AgentRunLinkView;
import ai.core.api.server.seoops.SeoOpsApiModels.ApprovalDecisionView;
import ai.core.api.server.seoops.SeoOpsApiModels.ApprovalPreviewView;
import ai.core.api.server.seoops.SeoOpsApiModels.ConversationLinkView;
import ai.core.api.server.seoops.SeoOpsApiModels.EvidenceRefView;
import ai.core.api.server.seoops.SeoOpsApiModels.IdNameView;
import ai.core.api.server.seoops.SeoOpsApiModels.LocationSummaryView;
import ai.core.api.server.seoops.SeoOpsApiModels.LocationView;
import ai.core.api.server.seoops.SeoOpsApiModels.MerchantSummaryView;
import ai.core.api.server.seoops.SeoOpsApiModels.MerchantView;
import ai.core.api.server.seoops.SeoOpsApiModels.PortfolioTotalsView;
import ai.core.api.server.seoops.SeoOpsApiModels.PortfolioView;
import ai.core.api.server.seoops.SeoOpsApiModels.ReportItemView;
import ai.core.api.server.seoops.SeoOpsApiModels.ReviewItemView;
import ai.core.api.server.seoops.SeoOpsApiModels.SeoTaskView;
import ai.core.api.server.seoops.SeoOpsApiModels.TaskEventView;
import ai.core.api.server.seoops.SeoOpsApiModels.TaskSummaryView;
import ai.core.server.seoops.SeoOpsQueryService.PortfolioData;
import ai.core.server.seoops.SeoOpsQueryService.ReportItem;
import ai.core.server.seoops.SeoOpsQueryService.ReviewItem;
import ai.core.server.seoops.domain.SeoEvidenceVerification;
import ai.core.server.seoops.domain.SeoLocation;
import ai.core.server.seoops.domain.SeoMerchant;
import ai.core.server.seoops.domain.SeoTask;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps persisted SEO Ops documents to bounded API views.
 *
 * @author xander
 */
public class SeoOpsViewMapper {
    private final Clock clock;

    public SeoOpsViewMapper() {
        this(Clock.systemUTC());
    }

    SeoOpsViewMapper(Clock clock) {
        this.clock = clock;
    }

    public PortfolioView portfolio(PortfolioData data) {
        var view = new PortfolioView();
        view.merchants = data.merchants().stream().map(merchant -> merchantSummary(merchant, data)).toList();
        view.totals = new PortfolioTotalsView();
        view.totals.tasks = view.merchants.stream().mapToLong(item -> item.taskCount).sum();
        view.totals.blocked = view.merchants.stream().mapToLong(item -> item.blockedCount).sum();
        view.totals.readyForApproval = view.merchants.stream().mapToLong(item -> item.readyForApprovalCount).sum();
        view.totals.overdue = view.merchants.stream().mapToLong(item -> item.overdueCount).sum();
        return view;
    }

    public MerchantView merchant(SeoMerchant merchant) {
        var view = new MerchantView();
        view.id = merchant.id;
        view.slug = merchant.slug;
        view.displayName = merchant.displayName;
        view.tags = List.copyOf(merchant.tags);
        view.operatorUserIds = List.copyOf(merchant.operatorUserIds);
        view.createdAt = merchant.createdAt;
        view.updatedAt = merchant.updatedAt;
        return view;
    }

    public LocationView location(SeoLocation location) {
        var view = new LocationView();
        view.id = location.id;
        view.merchantId = location.merchantId;
        view.slug = location.slug;
        view.displayName = location.displayName;
        view.timezone = location.timezone;
        view.externalIdentities = Map.copyOf(location.externalIdentities);
        view.readinessStatus = location.readinessStatus.name();
        view.missingRequirements = List.copyOf(location.missingRequirements);
        view.createdAt = location.createdAt;
        view.updatedAt = location.updatedAt;
        return view;
    }

    public TaskSummaryView taskSummary(SeoTask task, String merchantName, String locationName) {
        var view = new TaskSummaryView();
        populateTaskSummary(view, task, merchantName, locationName);
        return view;
    }

    public SeoTaskView task(SeoTask task, String merchantName, String locationName) {
        var view = new SeoTaskView();
        view.id = task.id;
        view.merchantId = task.merchantId;
        view.merchantName = merchantName;
        view.locationId = task.locationId;
        view.locationName = locationName;
        view.title = task.currentRevision.title;
        view.taskType = task.currentRevision.taskType;
        view.priority = task.currentRevision.priority;
        view.impact = task.currentRevision.impact;
        view.ownerId = task.ownerId;
        view.dueAt = task.dueAt;
        view.status = task.status.name();
        view.evidenceState = task.evidenceState.name();
        view.taskRevision = task.taskRevision;
        view.stateVersion = task.stateVersion;
        view.updatedAt = task.updatedAt;
        view.source = task.currentRevision.source;
        view.executionSpec = task.currentRevision.executionSpec;
        view.executionSpecHash = task.currentRevision.executionSpecHash;
        view.requiredEvidenceTypes = List.copyOf(task.currentRevision.requiredEvidenceTypes);
        view.evidenceRefs = task.evidenceRefs.stream().map(this::evidence).toList();
        view.approvalDecisions = task.approvalDecisions.stream().map(this::approvalDecision).toList();
        view.conversationLinks = task.conversationLinks.stream().map(this::conversationLink).toList();
        view.agentRunLinks = task.agentRunLinks.stream().map(this::agentRunLink).toList();
        view.createdAt = task.createdAt;
        return view;
    }

    public ApprovalPreviewView approvalPreview(ApprovalPreview preview) {
        var view = new ApprovalPreviewView();
        view.reviewable = preview.reviewable();
        view.blockers = List.copyOf(preview.blockers());
        view.taskRevision = preview.taskRevision();
        view.stateVersion = preview.stateVersion();
        view.executionSpecHash = preview.executionSpecHash();
        view.evidenceState = preview.evidenceState().name();
        view.currentStatus = preview.currentStatus().name();
        return view;
    }

    public TaskEventView event(SeoTask.TaskEvent event) {
        var view = new TaskEventView();
        view.id = event.id;
        view.type = event.type;
        view.actorId = event.actorId;
        view.fromStatus = event.fromStatus;
        view.toStatus = event.toStatus;
        view.taskRevision = event.taskRevision;
        view.resultingStateVersion = event.resultingStateVersion;
        view.referenceId = event.referenceId;
        view.occurredAt = event.occurredAt;
        return view;
    }

    public ReportItemView report(ReportItem item) {
        var view = new ReportItemView();
        view.taskId = item.task().id;
        view.merchantId = item.task().merchantId;
        view.locationId = item.task().locationId;
        view.evidenceId = item.evidence().id;
        view.reportType = item.evidence().type;
        view.artifactId = item.evidence().artifactId;
        view.fileId = item.evidence().fileId;
        view.sourceRef = item.evidence().sourceRef;
        view.sha256 = item.evidence().sha256;
        view.capturedAt = item.evidence().capturedAt;
        view.freshness = item.freshness();
        return view;
    }

    public ReviewItemView review(ReviewItem item) {
        var task = item.task();
        var evidence = verifiedCurrentEvidence(task);
        var view = new ReviewItemView();
        view.taskId = task.id;
        view.merchantId = task.merchantId;
        view.locationId = task.locationId;
        view.classification = item.classification();
        view.goal = task.currentRevision.title;
        view.baseline = sourceRef(evidence, "BASELINE_MEASUREMENT");
        view.action = sourceRef(evidence, "INTERVENTION_RECORD");
        view.observedChange = sourceRef(evidence, "POST_MEASUREMENT");
        view.competingExplanations = competingExplanations(item.classification());
        view.conclusionStrength = conclusionStrength(item.classification());
        view.followUpTest = followUpTest(item.classification());
        view.evidenceIds = evidence.stream().map(ref -> ref.id).toList();
        view.updatedAt = task.updatedAt;
        return view;
    }

    String freshness(ZonedDateTime capturedAt) {
        var days = Duration.between(capturedAt.toInstant(), clock.instant()).toDays();
        if (days <= 7) return "FRESH";
        if (days <= 30) return "AGING";
        return "STALE";
    }

    String reviewClassification(SeoTask task) {
        var types = verifiedCurrentEvidence(task).stream().map(ref -> ref.type).collect(java.util.stream.Collectors.toSet());
        var measured = types.containsAll(Set.of("BASELINE_MEASUREMENT", "INTERVENTION_RECORD", "POST_MEASUREMENT"));
        if (measured && types.contains("CAUSAL_DESIGN")) return "CAUSAL_READY";
        if (measured) return "CORRELATIONAL";
        if (types.contains("ACTION_EVENT") || types.contains("INTERVENTION_RECORD")
            || types.contains("POST_MEASUREMENT") || types.contains("BASELINE_MEASUREMENT")) return "FACTUAL";
        return "INSUFFICIENT_EVIDENCE";
    }

    private MerchantSummaryView merchantSummary(SeoMerchant merchant, PortfolioData data) {
        var counts = data.countsByMerchant().getOrDefault(merchant.id, SeoOpsQueryService.EMPTY_COUNTS);
        var view = new MerchantSummaryView();
        view.id = merchant.id;
        view.slug = merchant.slug;
        view.displayName = merchant.displayName;
        view.operatorUserIds = List.copyOf(merchant.operatorUserIds);
        view.operators = merchant.operatorUserIds.stream()
            .map(id -> idName(id, data.operatorNamesById().getOrDefault(id, id))).toList();
        view.ownerIds = counts.ownerIds().stream().sorted().toList();
        view.locations = data.locationsByMerchant().getOrDefault(merchant.id, List.of()).stream()
            .map(this::locationSummary).toList();
        view.locationCount = view.locations.size();
        view.taskCount = counts.tasks();
        view.blockedCount = counts.blocked();
        view.readyForApprovalCount = counts.readyForApproval();
        view.overdueCount = counts.overdue();
        view.health = health(counts);
        return view;
    }

    private void populateTaskSummary(TaskSummaryView view, SeoTask task, String merchantName, String locationName) {
        view.id = task.id;
        view.merchantId = task.merchantId;
        view.merchantName = merchantName;
        view.locationId = task.locationId;
        view.locationName = locationName;
        view.title = task.currentRevision.title;
        view.taskType = task.currentRevision.taskType;
        view.priority = task.currentRevision.priority;
        view.impact = task.currentRevision.impact;
        view.ownerId = task.ownerId;
        view.dueAt = task.dueAt;
        view.status = task.status.name();
        view.evidenceState = task.evidenceState.name();
        view.taskRevision = task.taskRevision;
        view.stateVersion = task.stateVersion;
        view.updatedAt = task.updatedAt;
    }

    private EvidenceRefView evidence(SeoTask.EvidenceRef evidence) {
        var view = new EvidenceRefView();
        view.id = evidence.id;
        view.taskRevision = evidence.taskRevision;
        view.type = evidence.type;
        view.artifactId = evidence.artifactId;
        view.fileId = evidence.fileId;
        view.sourceRef = evidence.sourceRef;
        view.sha256 = evidence.sha256;
        view.capturedAt = evidence.capturedAt;
        view.verificationStatus = evidence.verificationStatus.name();
        view.requirementKey = evidence.requirementKey;
        view.createdBy = evidence.createdBy;
        view.createdAt = evidence.createdAt;
        return view;
    }

    private ApprovalDecisionView approvalDecision(SeoTask.ApprovalDecision decision) {
        var view = new ApprovalDecisionView();
        view.id = decision.id;
        view.decision = decision.decision.name();
        view.reason = decision.reason;
        view.taskRevision = decision.taskRevision;
        view.executionSpecHash = decision.executionSpecHash;
        view.expectedStateVersion = decision.expectedStateVersion;
        view.resultingStateVersion = decision.resultingStateVersion;
        view.actorId = decision.actorId;
        view.decidedAt = decision.decidedAt;
        return view;
    }

    private ConversationLinkView conversationLink(SeoTask.ConversationLink link) {
        var view = new ConversationLinkView();
        view.conversationId = link.conversationId;
        view.relationship = link.relationship;
        view.linkedBy = link.linkedBy;
        view.linkedAt = link.linkedAt;
        return view;
    }

    private AgentRunLinkView agentRunLink(SeoTask.AgentRunLink link) {
        var view = new AgentRunLinkView();
        view.agentRunId = link.agentRunId;
        view.relationship = link.relationship;
        view.status = null;
        view.linkedBy = link.linkedBy;
        view.linkedAt = link.linkedAt;
        return view;
    }

    private LocationSummaryView locationSummary(SeoLocation location) {
        var view = new LocationSummaryView();
        view.id = location.id;
        view.displayName = location.displayName;
        view.readinessStatus = location.readinessStatus.name();
        return view;
    }

    private IdNameView idName(String id, String name) {
        var view = new IdNameView();
        view.id = id;
        view.name = name;
        return view;
    }

    private String health(SeoOpsQueryService.MerchantTaskCounts counts) {
        if (counts.blocked() > 0) return "BLOCKED";
        if (counts.overdue() > 0 || counts.readyForApproval() > 0) return "ATTENTION";
        return "STABLE";
    }

    private List<SeoTask.EvidenceRef> verifiedCurrentEvidence(SeoTask task) {
        if (task.evidenceRefs == null) return List.of();
        return task.evidenceRefs.stream()
            .filter(ref -> ref.verificationStatus == SeoEvidenceVerification.VERIFIED
                && task.taskRevision.equals(ref.taskRevision))
            .sorted(Comparator.comparing(ref -> ref.capturedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
    }

    private String sourceRef(List<SeoTask.EvidenceRef> evidence, String type) {
        return evidence.stream().filter(ref -> type.equals(ref.type)).map(ref -> ref.sourceRef)
            .filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
    }

    private List<String> competingExplanations(String classification) {
        if ("CAUSAL_READY".equals(classification)) return List.of();
        return List.of("seasonality", "concurrent changes", "measurement drift");
    }

    private String conclusionStrength(String classification) {
        return switch (classification) {
            case "CAUSAL_READY" -> "DESIGNED_FOR_CAUSAL_REVIEW";
            case "CORRELATIONAL" -> "CORRELATIONAL_ONLY";
            case "FACTUAL" -> "OBSERVATIONAL_ONLY";
            default -> "INSUFFICIENT";
        };
    }

    private String followUpTest(String classification) {
        if ("CAUSAL_READY".equals(classification)) return null;
        return "Capture a time-aligned baseline, intervention record, post measurement, and causal design.";
    }
}
