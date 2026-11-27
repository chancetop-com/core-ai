package ai.core.server.workspace;

/**
 * Parameters for creating a persistent workspace.
 *
 * @author stephen
 */
public record WorkspaceCreateSpec(
        String name,
        String agentId,
        Integer sizeGb,
        String profileId,
        Integer idleSleepMinutes,
        String bootstrapScript,
        String startScript) {
}
