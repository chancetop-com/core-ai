package ai.core.api.server.seoops;

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
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * @author xander
 */
public interface SeoOpsWebService {
    @GET
    @Path("/api/seo-ops/config")
    RuntimeConfigView config();

    @GET
    @Path("/api/seo-ops/portfolio")
    PortfolioView portfolio();

    @GET
    @Path("/api/seo-ops/inbox")
    ListTasksResponse inbox(PageRequest request);

    @GET
    @Path("/api/seo-ops/reviews")
    ListReviewsResponse reviews(PageRequest request);

    @GET
    @Path("/api/seo-ops/reports")
    ListReportsResponse reports(PageRequest request);

    @GET
    @Path("/api/seo-ops/tasks/:id")
    SeoTaskView task(@PathParam("id") String id);

    @GET
    @Path("/api/seo-ops/tasks/:id/events")
    ListEventsResponse events(@PathParam("id") String id, PageRequest request);

    @POST
    @Path("/api/seo-ops/merchants")
    MerchantView createMerchant(CreateMerchantRequest request);

    @POST
    @Path("/api/seo-ops/merchants/:id/locations")
    LocationView createLocation(@PathParam("id") String merchantId, CreateLocationRequest request);

    @POST
    @Path("/api/seo-ops/tasks")
    SeoTaskView createTask(CreateTaskRequest request);

    @POST
    @Path("/api/seo-ops/tasks/:id/revisions")
    SeoTaskView createRevision(@PathParam("id") String id, CreateRevisionRequest request);

    @POST
    @Path("/api/seo-ops/tasks/:id/evidence")
    SeoTaskView appendEvidence(@PathParam("id") String id, AppendEvidenceRequest request);

    @POST
    @Path("/api/seo-ops/tasks/:id/conversation-links")
    SeoTaskView linkConversation(@PathParam("id") String id, LinkConversationRequest request);

    @POST
    @Path("/api/seo-ops/tasks/:id/approval-previews")
    ApprovalPreviewView approvalPreview(@PathParam("id") String id, ApprovalPreviewRequest request);

    @POST
    @Path("/api/seo-ops/tasks/:id/approval-decisions")
    SeoTaskView approvalDecision(@PathParam("id") String id, ApprovalDecisionRequest request);
}
