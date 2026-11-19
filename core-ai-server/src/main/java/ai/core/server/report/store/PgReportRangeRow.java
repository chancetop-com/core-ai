package ai.core.server.report.store;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * One row of aggregate metadata for a report query.
 *
 * @author core-ai
 */
public class PgReportRangeRow {
    @Column(name = "matched")
    public Long matched;

    @Column(name = "source_time")
    public ZonedDateTime sourceTime;

    @Column(name = "source_updated_at")
    public ZonedDateTime sourceUpdatedAt;
}
