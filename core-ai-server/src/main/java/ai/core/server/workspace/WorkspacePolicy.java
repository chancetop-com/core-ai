package ai.core.server.workspace;

import ai.core.server.domain.AgentSandboxConfig;
import ai.core.server.sandbox.SandboxService;

/**
 * How a new session should treat persistent workspaces. {@code enabled == null} follows the
 * deployment switch; {@code false} explicitly opts out (agent config decides), and {@code true}
 * requires a workspace. Size/profile are creation parameters of the default workspace only.
 *
 * @author stephen
 */
public record WorkspacePolicy(Boolean enabled, Integer sizeGb, String profile) {
    public static WorkspacePolicy inherit() {
        return new WorkspacePolicy(null, null, null);
    }

    public static WorkspacePolicy disabled() {
        return new WorkspacePolicy(Boolean.FALSE, null, null);
    }

    /**
     * Workspace opt-in lives in the agent sandbox config. A disabled sandbox and an agent without
     * sandbox config both land here: the sandbox must be able to run before a workspace can be bound.
     */
    public static WorkspacePolicy of(AgentSandboxConfig agentConfig, SandboxService sandboxService) {
        if (!sandboxService.isSandboxEnabled(sandboxService.getEffectiveConfig(agentConfig))) return disabled();
        if (agentConfig == null) return inherit();
        return new WorkspacePolicy(agentConfig.workspaceEnabled, agentConfig.workspaceSizeGb, agentConfig.workspaceProfile);
    }
}
