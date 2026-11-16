package ai.core.server.web;

import ai.core.api.server.SkillQualityAdminWebService;
import ai.core.api.server.skill.SkillQualityRuleListResponse;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.skill.quality.SkillQualityAdminService;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;

/**
 * @author core-ai
 */
public class SkillQualityAdminWebServiceImpl implements SkillQualityAdminWebService {
    @Inject
    WebContext webContext;
    @Inject
    SkillQualityAdminService adminService;

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public SkillQualityRuleListResponse rules() {
        return adminService.rules(AuthContext.userId(webContext));
    }
}
