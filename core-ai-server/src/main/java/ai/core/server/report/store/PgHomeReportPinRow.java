package ai.core.server.report.store;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author core-ai
 */
public class PgHomeReportPinRow {
    @Column(name = "id")
    public String id;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "report_id")
    public String reportId;

    @Column(name = "position")
    public Integer position;

    @Column(name = "width")
    public String width;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;
}
