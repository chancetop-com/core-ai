package ai.core.server.report.application;

import ai.core.server.report.domain.ReportConstants;
import ai.core.server.report.domain.ReportSpec;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportQueryServiceTest {
    private final ReportQueryService service = new ReportQueryService();

    @Test
    void resolvesCompleteLocalDaysAcrossTimezone() {
        var spec = new ReportSpec();
        spec.query = new ReportSpec.Query();
        spec.query.timeRange = new ReportSpec.TimeRange();
        spec.query.timeRange.kind = ReportConstants.RANGE_LAST_N_COMPLETE_DAYS;
        spec.query.timeRange.days = 7;
        spec.query.timezone = "Asia/Shanghai";

        var range = service.resolveRange(spec, ZonedDateTime.parse("2026-11-19T01:00:00Z"), null);

        assertEquals(ZonedDateTime.parse("2026-11-11T16:00:00Z"), range.from().withZoneSameInstant(ZoneOffset.UTC));
        assertEquals(ZonedDateTime.parse("2026-11-18T16:00:00Z"), range.to().withZoneSameInstant(ZoneOffset.UTC));
    }

    @Test
    void resolvesRollingHoursWithoutLocalCalendar() {
        var spec = new ReportSpec();
        spec.query = new ReportSpec.Query();
        spec.query.timeRange = new ReportSpec.TimeRange();
        spec.query.timeRange.kind = ReportConstants.RANGE_ROLLING_DAYS;
        spec.query.timeRange.days = 7;
        spec.query.timezone = "Asia/Shanghai";

        var range = service.resolveRange(spec, ZonedDateTime.parse("2026-11-19T01:00:00Z"), null);

        assertEquals(ZonedDateTime.parse("2026-11-12T01:00:00Z"), range.from().withZoneSameInstant(ZoneOffset.UTC));
        assertEquals(ZonedDateTime.parse("2026-11-19T01:00:00Z"), range.to().withZoneSameInstant(ZoneOffset.UTC));
    }
}
