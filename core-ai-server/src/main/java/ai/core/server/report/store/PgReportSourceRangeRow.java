package ai.core.server.report.store;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * Min/max values of the report time field, used to anchor ranges to the data instead of the wall clock.
 *
 * @author core-ai
 */
public class PgReportSourceRangeRow {
    @Column(name = "min_time")
    public ZonedDateTime minTime;

    @Column(name = "max_time")
    public ZonedDateTime maxTime;
}
