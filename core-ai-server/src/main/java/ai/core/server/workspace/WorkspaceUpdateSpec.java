package ai.core.server.workspace;

/**
 * Editable workspace metadata and optional target image/template for upgrade.
 *
 * @author stephen
 */
public record WorkspaceUpdateSpec(String name, Integer idleSleepMinutes, String bootstrapScript,
                                  String startScript, String image, String template) {
}
