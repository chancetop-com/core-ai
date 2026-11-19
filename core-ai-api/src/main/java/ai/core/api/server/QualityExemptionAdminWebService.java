package ai.core.api.server;

import ai.core.api.server.quality.AddQualityExemptionRequest;
import ai.core.api.server.quality.QualityExemptionListResponse;
import ai.core.api.server.quality.QualityExemptionSubjectListRequest;
import ai.core.api.server.quality.QualityExemptionSubjectListResponse;
import ai.core.api.server.quality.RemoveQualityExemptionRequest;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;

/**
 * Whitelist administration shared by agent and skill publish quality gates. Every method requires a
 * human platform admin at the service layer; the route-level RBAC annotation is not sufficient
 * because API users bypass action checks.
 *
 * @author core-ai
 */
public interface QualityExemptionAdminWebService {
    @GET
    @Path("/api/admin/quality-exemptions")
    QualityExemptionListResponse list();

    @GET
    @Path("/api/admin/quality-exemptions/subjects")
    QualityExemptionSubjectListResponse subjects(QualityExemptionSubjectListRequest request);

    @POST
    @Path("/api/admin/quality-exemptions")
    QualityExemptionListResponse add(AddQualityExemptionRequest request);

    @POST
    @Path("/api/admin/quality-exemptions/remove")
    QualityExemptionListResponse remove(RemoveQualityExemptionRequest request);
}
