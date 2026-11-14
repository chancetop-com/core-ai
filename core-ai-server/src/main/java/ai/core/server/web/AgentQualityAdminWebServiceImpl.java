package ai.core.server.web;

import ai.core.api.server.AgentQualityAdminWebService;
import ai.core.api.server.agent.ActivateAgentQualityPolicyRequest;
import ai.core.api.server.agent.AgentQualityPolicyListResponse;
import ai.core.api.server.agent.AgentQualityPolicyView;
import ai.core.api.server.agent.AgentQualityRuleListResponse;
import ai.core.api.server.agent.AgentQualityRuleTemplateListResponse;
import ai.core.api.server.agent.AgentQualityRuleView;
import ai.core.api.server.agent.CreateAgentQualityRuleRequest;
import ai.core.api.server.agent.TestAgentQualityRuleRequest;
import ai.core.api.server.agent.TestAgentQualityRuleResponse;
import ai.core.api.server.agent.UpdateAgentQualityRuleRequest;
import ai.core.server.quality.AgentQualityAdminService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.WebContext;

/**
 * @author core-ai
 */
public class AgentQualityAdminWebServiceImpl implements AgentQualityAdminWebService {
    @Inject
    WebContext webContext;
    @Inject
    AgentQualityAdminService adminService;

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public AgentQualityRuleTemplateListResponse templates() {
        return adminService.templates(AuthContext.userId(webContext));
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public AgentQualityRuleListResponse rules() {
        return adminService.rules(AuthContext.userId(webContext));
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public AgentQualityRuleView createRule(CreateAgentQualityRuleRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        return adminService.createRule(request, userId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public AgentQualityRuleView updateRule(String id, UpdateAgentQualityRuleRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("rule_id", id);
        return adminService.updateRule(id, request, userId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public TestAgentQualityRuleResponse testRule(String id, TestAgentQualityRuleRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("rule_id", id);
        return adminService.testRule(id, request, userId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public AgentQualityRuleView retireRule(String id) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("rule_id", id);
        return adminService.retireRule(id, userId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public AgentQualityPolicyListResponse policies() {
        return adminService.policies(AuthContext.userId(webContext));
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public AgentQualityPolicyView activatePolicy(ActivateAgentQualityPolicyRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("change_reason", request.changeReason);
        return adminService.activate(request, userId);
    }
}
