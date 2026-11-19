package ai.core.server.report.store;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author core-ai
 */
public class PgHomeReportLayoutRow {
    @Column(name = "user_id")
    public String userId;

    @Column(name = "revision")
    public Long revision;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;
}
