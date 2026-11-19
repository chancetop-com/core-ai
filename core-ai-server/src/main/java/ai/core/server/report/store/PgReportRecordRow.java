package ai.core.server.report.store;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author core-ai
 */
public class PgReportRecordRow {
    @Column(name = "id")
    public String id;

    @Column(name = "run_started_at")
    public ZonedDateTime runStartedAt;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "data")
    public String data;
}
