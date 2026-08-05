package ai.core.server.workflow;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentStatus;

/**
 * @author Xander
 */
public final class WorkflowAgentAccessPolicy {
    public static boolean isOwnedEditable(AgentDefinition agent, String userId) {
        return agent != null
               && !Boolean.TRUE.equals(agent.systemDefault)
               && userId != null
               && userId.equals(agent.userId);
    }

    static boolean hasUsablePublishedConfig(AgentDefinition agent) {
        return agent != null && agent.status == AgentStatus.PUBLISHED && agent.publishedConfig != null;
    }

    public static boolean canReference(AgentDefinition agent, String userId) {
        return isOwnedEditable(agent, userId) || hasUsablePublishedConfig(agent);
    }

    private WorkflowAgentAccessPolicy() {
    }
}
