package ai.core.api.server.skill;

import core.framework.api.web.service.QueryParam;

/**
 * Query parameters for GET /api/admin/skill-quality/policies.
 *
 * @author stephen
 */
public class ListSkillQualityPoliciesRequest {
    @QueryParam(name = "scope")
    public String scope;
}
