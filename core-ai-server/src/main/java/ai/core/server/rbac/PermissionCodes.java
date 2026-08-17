package ai.core.server.rbac;

import java.util.List;

/**
 * RBAC action permission codes (catalog defined in docs/cn/design-rbac-permission.md).
 * Convention: {@code <domain>.<action>}; {@code manage} implies {@code view}.
 *
 * @author stephen
 */
public final class PermissionCodes {
    public static final String DASHBOARD_VIEW = "dashboard.view";
    public static final String CHAT_USE = "chat.use";
    public static final String TRACE_VIEW = "trace.view";

    public static final String AGENT_VIEW = "agent.view";
    public static final String AGENT_MANAGE = "agent.manage";
    public static final String WORKFLOW_VIEW = "workflow.view";
    public static final String WORKFLOW_MANAGE = "workflow.manage";
    public static final String PROMPT_VIEW = "prompt.view";
    public static final String PROMPT_MANAGE = "prompt.manage";
    public static final String TRIGGER_VIEW = "trigger.view";
    public static final String TRIGGER_MANAGE = "trigger.manage";
    public static final String TASK_VIEW = "task.view";

    public static final String TOOL_VIEW = "tool.view";
    public static final String MCP_VIEW = "mcp.view";
    public static final String MCP_MANAGE = "mcp.manage";
    public static final String APITOOL_VIEW = "apitool.view";
    public static final String APITOOL_MANAGE = "apitool.manage";
    public static final String SKILL_VIEW = "skill.view";
    public static final String SKILL_MANAGE = "skill.manage";
    public static final String DATASET_VIEW = "dataset.view";
    public static final String DATASET_MANAGE = "dataset.manage";
    public static final String EXPERIMENT_VIEW = "experiment.view";
    public static final String NOTIFICATION_VIEW = "notification.view";

    public static final String GATEWAY_MANAGE = "gateway.manage";
    public static final String SYSTEM_MANAGE = "system.manage";
    public static final String COSTALERT_MANAGE = "costalert.manage";
    public static final String USER_MANAGE = "user.manage";
    public static final String ANALYTICS_VIEW = "analytics.view";
    public static final String RBAC_MANAGE = "rbac.manage";
    public static final String SEOOPS_VIEW = "seoops.view";
    public static final String SEOOPS_MANAGE = "seoops.manage";
    public static final String SEOOPS_APPROVE = "seoops.approve";

    /** Complete catalog, used for role config validation and admin UI checkboxes. */
    public static final List<String> ALL = List.of(
            DASHBOARD_VIEW, CHAT_USE, TRACE_VIEW,
            AGENT_VIEW, AGENT_MANAGE,
            WORKFLOW_VIEW, WORKFLOW_MANAGE,
            PROMPT_VIEW, PROMPT_MANAGE,
            TRIGGER_VIEW, TRIGGER_MANAGE,
            TASK_VIEW,
            TOOL_VIEW, MCP_VIEW, MCP_MANAGE,
            APITOOL_VIEW, APITOOL_MANAGE,
            SKILL_VIEW, SKILL_MANAGE,
            DATASET_VIEW, DATASET_MANAGE,
            EXPERIMENT_VIEW, NOTIFICATION_VIEW,
            GATEWAY_MANAGE, SYSTEM_MANAGE, COSTALERT_MANAGE,
            USER_MANAGE, ANALYTICS_VIEW, RBAC_MANAGE,
            SEOOPS_VIEW, SEOOPS_MANAGE, SEOOPS_APPROVE);

    private PermissionCodes() {
    }
}
