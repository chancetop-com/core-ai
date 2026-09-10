package ai.core.server.agent;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;

/**
 * Visibility and runnability of an agent for a given caller, shared by every execution surface
 * (chat, A2A protocol, Agent Hub). Both rules mirror the execution path exactly, so a caller can
 * never see an agent the execution layer would reject:
 * <ul>
 *   <li>visible: published, or owned by the caller, or the system default agent;</li>
 *   <li>runnable: the owner's live draft for AGENT, otherwise a usable published snapshot —
 *       an LLM_CALL always runs its published config.</li>
 * </ul>
 *
 * @author stephen
 */
public final class AgentVisibility {
    public static boolean isVisible(AgentDefinition definition, String callerUserId) {
        if (definition == null) return false;
        if (definition.status == AgentStatus.PUBLISHED) return true;
        if (callerUserId != null && callerUserId.equals(definition.userId)) return true;
        return Boolean.TRUE.equals(definition.systemDefault);
    }

    public static boolean isRunnable(AgentDefinition definition, String callerUserId) {
        if (definition == null) return false;
        if (definition.type == DefinitionType.LLM_CALL) {
            return AgentDependencyAccessPolicy.hasUsablePublishedLlmCall(definition);
        }
        if (AgentDependencyAccessPolicy.isOwnedEditable(definition, callerUserId)) return true;
        return AgentDependencyAccessPolicy.hasUsablePublishedConfig(definition);
    }

    /**
     * The owner's own draft is executable as-is; everyone else runs the published snapshot, which
     * may be missing even when the agent is visible (system default agent still in draft).
     */
    public static boolean runsLiveDraft(AgentDefinition definition, String callerUserId) {
        if (definition == null || definition.type == DefinitionType.LLM_CALL) return false;
        return AgentDependencyAccessPolicy.isOwnedEditable(definition, callerUserId);
    }

    private AgentVisibility() {
    }
}
