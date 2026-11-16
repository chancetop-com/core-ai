package ai.core.api.server;

import ai.core.api.server.skill.SkillQualityRuleListResponse;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;

/**
 * Read-only catalog of the built-in skill quality rules. The rule set and its thresholds are versioned with
 * the code; this service exists so administrators can see what the publish gate enforces, not to edit it.
 *
 * @author core-ai
 */
public interface SkillQualityAdminWebService {
    @GET
    @Path("/api/admin/skill-quality/rules")
    SkillQualityRuleListResponse rules();
}
