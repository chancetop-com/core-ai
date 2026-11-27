package ai.core.server.workspace;

import java.time.ZonedDateTime;

/**
 * Persisted bootstrap, start script, log snippet, and env-file probe state for a workspace.
 *
 * @author stephen
 */
public record WorkspaceBootstrapState(
        ZonedDateTime bootstrapCompletedAt,
        String bootstrapScriptHash,
        String startScriptHash,
        String lastBootstrapLog,
        boolean envFilePresent) {
}
