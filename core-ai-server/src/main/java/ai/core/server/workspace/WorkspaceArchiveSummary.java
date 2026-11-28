package ai.core.server.workspace;

import java.time.ZonedDateTime;

/**
 * Aggregated READY archive summary attached to a workspace view.
 *
 * @author stephen
 */
public record WorkspaceArchiveSummary(long archiveCount, ZonedDateTime latestArchiveAt) {
}
