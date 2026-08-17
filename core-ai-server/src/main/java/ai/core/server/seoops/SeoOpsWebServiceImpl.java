package ai.core.server.seoops;

import ai.core.api.server.seoops.SeoOpsApiModels.AppendEvidenceRequest;
import ai.core.api.server.seoops.SeoOpsApiModels.ApprovalDecisionRequest;
import ai.core.api.server.seoops.SeoOpsApiModels.ApprovalPreviewRequest;
import ai.core.api.server.seoops.SeoOpsApiModels.ApprovalPreviewView;
import ai.core.api.server.seoops.SeoOpsApiModels.CreateLocationRequest;
import ai.core.api.server.seoops.SeoOpsApiModels.CreateMerchantRequest;
import ai.core.api.server.seoops.SeoOpsApiModels.CreateRevisionRequest;
import ai.core.api.server.seoops.SeoOpsApiModels.CreateTaskRequest;
import ai.core.api.server.seoops.SeoOpsApiModels.LinkConversationRequest;
import ai.core.api.server.seoops.SeoOpsApiModels.ListEventsResponse;
import ai.core.api.server.seoops.SeoOpsApiModels.ListReportsResponse;
import ai.core.api.server.seoops.SeoOpsApiModels.ListReviewsResponse;
import ai.core.api.server.seoops.SeoOpsApiModels.ListTasksResponse;
import ai.core.api.server.seoops.SeoOpsApiModels.LocationView;
import ai.core.api.server.seoops.SeoOpsApiModels.MerchantView;
import ai.core.api.server.seoops.SeoOpsApiModels.PageRequest;
import ai.core.api.server.seoops.SeoOpsApiModels.PortfolioView;
import ai.core.api.server.seoops.SeoOpsApiModels.RuntimeConfigView;
import ai.core.api.server.seoops.SeoOpsApiModels.SeoTaskView;
import ai.core.api.server.seoops.SeoOpsWebService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.seoops.domain.SeoTask;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.WebContext;
import core.framework.web.exception.UnauthorizedException;

/**
 * Guarded transport adapter for the SEO operations control plane.
 *
 * @author xander
 */
public class SeoOpsWebServiceImpl implements SeoOpsWebService {
    @Inject
    WebContext webContext;

    @Inject
    SeoOpsRuntimeConfig runtimeConfig;

    @Inject
    SeoCopilotPolicy copilotPolicy;

    @Inject
    SeoMerchantService merchantService;

    @Inject
    SeoTaskCommandService taskService;

    @Inject
    SeoOpsQueryService queryService;

    @Inject
    SeoOpsViewMapper viewMapper;

    @Override
    @PermissionsRequired(PermissionCodes.SEOOPS_VIEW)
    public RuntimeConfigView config() {
        actor();
        var eligible = copilotPolicy.eligibleAgentId();
        var view = new RuntimeConfigView();
        view.copilotEnabled = eligible.isPresent();
        view.copilotAgentId = eligible.orElse(null);
        return view;
    }

    @Override
    @PermissionsRequired(PermissionCodes.SEOOPS_VIEW)
    public PortfolioView portfolio() {
        return viewMapper.portfolio(queryService.portfolio(actor()));
    }

    @Override
    @PermissionsRequired(PermissionCodes.SEOOPS_VIEW)
    public ListTasksResponse inbox(PageRequest request) {
        var page = queryService.inbox(actor(), request);
        var merchantNames = queryService.merchantNames(page.items());
        var locationNames = queryService.locationNames(page.items());
        var response = new ListTasksResponse();
        response.items = page.items().stream().map(task -> viewMapper.taskSummary(task,
            merchantNames.getOrDefault(task.merchantId, task.merchantId), locationNames.get(task.locationId))).toList();
        applyPage(response, page);
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.SEOOPS_VIEW)
    public ListReviewsResponse reviews(PageRequest request) {
        var page = queryService.reviews(actor(), request);
        var response = new ListReviewsResponse();
        response.items = page.items().stream().map(viewMapper::review).toList();
        response.offset = page.offset();
        response.limit = page.limit();
        response.total = page.total();
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.SEOOPS_VIEW)
    public ListReportsResponse reports(PageRequest request) {
        var page = queryService.reports(actor(), request);
        var response = new ListReportsResponse();
        response.items = page.items().stream().map(viewMapper::report).toList();
        response.offset = page.offset();
        response.limit = page.limit();
        response.total = page.total();
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.SEOOPS_VIEW)
    public SeoTaskView task(String id) {
        var actor = actor();
        return taskView(actor, queryService.requireVisibleTask(actor, id));
    }

    @Override
    @PermissionsRequired(PermissionCodes.SEOOPS_VIEW)
    public ListEventsResponse events(String id, PageRequest request) {
        var page = queryService.events(actor(), id, request);
        var response = new ListEventsResponse();
        response.items = page.items().stream().map(viewMapper::event).toList();
        response.offset = page.offset();
        response.limit = page.limit();
        response.total = page.total();
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.SEOOPS_MANAGE)
    public MerchantView createMerchant(CreateMerchantRequest request) {
        var actor = actor();
        ActionLogContext.put("seoops_actor_id", actor);
        return viewMapper.merchant(merchantService.createMerchant(actor, request));
    }

    @Override
    @PermissionsRequired(PermissionCodes.SEOOPS_MANAGE)
    public LocationView createLocation(String merchantId, CreateLocationRequest request) {
        var actor = actor();
        action(actor, merchantId, null);
        return viewMapper.location(merchantService.createLocation(actor, merchantId, request));
    }

    @Override
    @PermissionsRequired(PermissionCodes.SEOOPS_MANAGE)
    public SeoTaskView createTask(CreateTaskRequest request) {
        var actor = actor();
        var task = taskService.createTask(actor, request);
        action(actor, task.merchantId, task.id);
        return taskView(actor, task);
    }

    @Override
    @PermissionsRequired(PermissionCodes.SEOOPS_MANAGE)
    public SeoTaskView createRevision(String id, CreateRevisionRequest request) {
        var actor = actor();
        return mutationView(actor, taskService.createRevision(actor, id, request));
    }

    @Override
    @PermissionsRequired(PermissionCodes.SEOOPS_MANAGE)
    public SeoTaskView appendEvidence(String id, AppendEvidenceRequest request) {
        var actor = actor();
        return mutationView(actor, taskService.appendEvidence(actor, id, request));
    }

    @Override
    @PermissionsRequired(PermissionCodes.SEOOPS_MANAGE)
    public SeoTaskView linkConversation(String id, LinkConversationRequest request) {
        var actor = actor();
        return mutationView(actor, taskService.linkConversation(actor, id, request));
    }

    @Override
    @PermissionsRequired(PermissionCodes.SEOOPS_APPROVE)
    public ApprovalPreviewView approvalPreview(String id, ApprovalPreviewRequest request) {
        var actor = actor();
        ActionLogContext.put("seoops_task_id", id);
        return viewMapper.approvalPreview(taskService.preview(actor, id, request));
    }

    @Override
    @PermissionsRequired(PermissionCodes.SEOOPS_APPROVE)
    public SeoTaskView approvalDecision(String id, ApprovalDecisionRequest request) {
        var actor = actor();
        return mutationView(actor, taskService.decide(actor, id, request));
    }

    private SeoTaskView mutationView(String actor, SeoTask task) {
        action(actor, task.merchantId, task.id);
        ActionLogContext.put("seoops_task_revision", task.taskRevision);
        ActionLogContext.put("seoops_state_version", task.stateVersion);
        return taskView(actor, task);
    }

    private SeoTaskView taskView(String actor, SeoTask task) {
        var merchant = merchantService.requireVisibleMerchant(actor, task.merchantId);
        var locationName = task.locationId == null ? null
            : merchantService.requireVisibleLocation(actor, task.merchantId, task.locationId).displayName;
        return viewMapper.task(task, merchant.displayName, locationName);
    }

    private void applyPage(ListTasksResponse response, SeoOpsQueryService.Page<SeoTask> page) {
        response.offset = page.offset();
        response.limit = page.limit();
        response.total = page.total();
    }

    private String actor() {
        var actor = AuthContext.userId(webContext);
        if (actor == null || actor.isBlank()) throw new UnauthorizedException("unauthorized");
        ActionLogContext.put("seoops_actor_id", actor);
        return actor;
    }

    private void action(String actor, String merchantId, String taskId) {
        ActionLogContext.put("seoops_actor_id", actor);
        ActionLogContext.put("seoops_merchant_id", merchantId);
        if (taskId != null) ActionLogContext.put("seoops_task_id", taskId);
    }
}
