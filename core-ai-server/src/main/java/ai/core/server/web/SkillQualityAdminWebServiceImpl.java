package ai.core.server.web;

import ai.core.api.server.SkillQualityAdminWebService;
import ai.core.api.server.skill.ActivateSkillQualityPolicyRequest;
import ai.core.api.server.skill.CreateSkillQualityRuleRequest;
import ai.core.api.server.skill.ListSkillQualityPoliciesRequest;
import ai.core.api.server.skill.ListSkillQualityRulesRequest;
import ai.core.api.server.skill.SkillDefinitionView;
import ai.core.api.server.skill.SkillQualityPolicyListResponse;
import ai.core.api.server.skill.SkillQualityPolicyView;
import ai.core.api.server.skill.SkillQualityRuleListResponse;
import ai.core.api.server.skill.SkillQualityRuleTemplateListResponse;
import ai.core.api.server.skill.SkillQualityRuleView;
import ai.core.api.server.skill.TestSkillQualityRuleRequest;
import ai.core.api.server.skill.TestSkillQualityRuleResponse;
import ai.core.api.server.skill.UpdateSkillQualityRuleRequest;
import ai.core.api.server.skill.UpdateSkillTierRequest;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.skill.SkillTierAdminService;
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
    @Inject
    SkillTierAdminService tierAdminService;

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public SkillQualityRuleTemplateListResponse templates() {
        return adminService.templates(AuthContext.userId(webContext));
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public SkillQualityRuleListResponse rules(ListSkillQualityRulesRequest request) {
        return adminService.rules(AuthContext.userId(webContext), request == null ? null : request.scope);
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public SkillQualityRuleView createRule(CreateSkillQualityRuleRequest request) {
        return adminService.createRule(request, AuthContext.userId(webContext));
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public SkillQualityRuleView updateRule(String id, UpdateSkillQualityRuleRequest request) {
        return adminService.updateRule(id, request, AuthContext.userId(webContext));
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public TestSkillQualityRuleResponse testRule(String id, TestSkillQualityRuleRequest request) {
        return adminService.testRule(id, request, AuthContext.userId(webContext));
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public SkillQualityRuleView retireRule(String id) {
        return adminService.retireRule(id, AuthContext.userId(webContext));
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public SkillQualityPolicyListResponse policies(ListSkillQualityPoliciesRequest request) {
        return adminService.policies(AuthContext.userId(webContext), request == null ? null : request.scope);
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public SkillQualityPolicyView activatePolicy(ActivateSkillQualityPolicyRequest request) {
        return adminService.activate(request, AuthContext.userId(webContext));
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public SkillDefinitionView updateTier(String id, UpdateSkillTierRequest request) {
        return tierAdminService.updateTier(id, request, AuthContext.userId(webContext));
    }
}
