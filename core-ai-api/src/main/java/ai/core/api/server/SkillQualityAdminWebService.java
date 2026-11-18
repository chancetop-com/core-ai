package ai.core.api.server;

import ai.core.api.server.skill.ActivateSkillQualityPolicyRequest;
import ai.core.api.server.skill.CreateSkillQualityRuleRequest;
import ai.core.api.server.skill.SkillQualityPolicyListResponse;
import ai.core.api.server.skill.SkillQualityPolicyView;
import ai.core.api.server.skill.SkillQualityRuleListResponse;
import ai.core.api.server.skill.SkillQualityRuleTemplateListResponse;
import ai.core.api.server.skill.SkillQualityRuleView;
import ai.core.api.server.skill.TestSkillQualityRuleRequest;
import ai.core.api.server.skill.TestSkillQualityRuleResponse;
import ai.core.api.server.skill.UpdateSkillQualityRuleRequest;
import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;
import core.framework.api.web.service.ResponseStatus;

/**
 * Skill publish-policy administration. Every method requires a human platform admin at the service
 * layer; the route-level RBAC annotation is not sufficient because API users bypass action checks.
 *
 * @author core-ai
 */
public interface SkillQualityAdminWebService {
    @GET
    @Path("/api/admin/skill-quality/rule-templates")
    SkillQualityRuleTemplateListResponse templates();

    @GET
    @Path("/api/admin/skill-quality/rules")
    SkillQualityRuleListResponse rules();

    @POST
    @Path("/api/admin/skill-quality/rules")
    @ResponseStatus(HTTPStatus.CREATED)
    SkillQualityRuleView createRule(CreateSkillQualityRuleRequest request);

    @PUT
    @Path("/api/admin/skill-quality/rules/:id")
    SkillQualityRuleView updateRule(@PathParam("id") String id, UpdateSkillQualityRuleRequest request);

    @POST
    @Path("/api/admin/skill-quality/rules/:id/test")
    TestSkillQualityRuleResponse testRule(@PathParam("id") String id, TestSkillQualityRuleRequest request);

    @POST
    @Path("/api/admin/skill-quality/rules/:id/retire")
    SkillQualityRuleView retireRule(@PathParam("id") String id);

    @GET
    @Path("/api/admin/skill-quality/policies")
    SkillQualityPolicyListResponse policies();

    @POST
    @Path("/api/admin/skill-quality/policies/activate")
    SkillQualityPolicyView activatePolicy(ActivateSkillQualityPolicyRequest request);
}
