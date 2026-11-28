package ai.core.server.workspace;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgWorkspaceArchiveSummaryRow {
    @Column(name = "workspace_id")
    public String workspaceId;

    @Column(name = "archive_count")
    public Long archiveCount;

    @Column(name = "latest_archive_at")
    public ZonedDateTime latestArchiveAt;
}
