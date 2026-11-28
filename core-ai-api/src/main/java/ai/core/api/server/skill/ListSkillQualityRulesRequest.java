package ai.core.api.server.skill;

import core.framework.api.web.service.QueryParam;

/**
 * Query parameters for GET /api/admin/skill-quality/rules.
 *
 * @author stephen
 */
public class ListSkillQualityRulesRequest {
    @QueryParam(name = "scope")
    public String scope;
}
