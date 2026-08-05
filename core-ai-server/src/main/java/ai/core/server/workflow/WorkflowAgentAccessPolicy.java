package ai.core.server.workflow;

import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.domain.AgentDefinition;

/**
 * @author Xander
 */
public final class WorkflowAgentAccessPolicy {
    public static boolean isOwnedEditable(AgentDefinition agent, String userId) {
        return AgentDependencyAccessPolicy.isOwnedEditable(agent, userId);
    }

    static boolean hasUsablePublishedConfig(AgentDefinition agent) {
        return AgentDependencyAccessPolicy.hasUsablePublishedConfig(agent);
    }

    static boolean hasUsablePublishedSubAgent(AgentDefinition agent) {
        return AgentDependencyAccessPolicy.hasUsablePublishedSubAgent(agent);
    }

    public static boolean canReference(AgentDefinition agent, String userId) {
        return isOwnedEditable(agent, userId) || hasUsablePublishedConfig(agent);
    }

    private WorkflowAgentAccessPolicy() {
    }
}
