package ai.core.server.project;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgProjectRow {
    @Column(name = "id")
    public String id;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "status")
    public String status;

    @Column(name = "stats_dirty")
    public Boolean statsDirty;

    @Column(name = "analysis_status")
    public String analysisStatus;

    @Column(name = "last_analyzed_at")
    public ZonedDateTime lastAnalyzedAt;

    @Column(name = "last_analysis_at")
    public ZonedDateTime lastAnalysisAt;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Column(name = "archived_at")
    public ZonedDateTime archivedAt;

    @Column(name = "data")
    public String data;
}
