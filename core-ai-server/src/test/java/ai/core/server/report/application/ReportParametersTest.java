package ai.core.server.report.application;

import ai.core.server.report.domain.ReportConstants;
import ai.core.server.report.domain.ReportSpec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportParametersTest {
    @Test
    void resolvesRangeAndInFilter() {
        var query = query();
        var range = new ReportSpec.TimeRange();
        range.kind = ReportConstants.RANGE_LAST_N_COMPLETE_DAYS;
        range.days = 7;
        var bindings = new LinkedHashMap<String, Object>();
        bindings.put("range", range);
        bindings.put("stores", List.of("web", "app"));

        var resolved = ReportParameters.resolve(query, bindings);

        assertEquals(ReportConstants.RANGE_LAST_N_COMPLETE_DAYS, resolved.timeRange.kind);
        assertEquals(7, resolved.timeRange.days);
        assertEquals(List.of("web", "app"), resolved.filters.get(0).value);
        assertNull(resolved.filters.get(0).param);
    }

    @Test
    void dropsEmptyInParameter() {
        var query = query();
        var bindings = new LinkedHashMap<String, Object>();
        bindings.put("range", range());
        bindings.put("stores", List.of());

        var resolved = ReportParameters.resolve(query, bindings);

        assertTrue(resolved.filters.isEmpty());
    }

    @Test
    void requiresMissingParameter() {
        var error = assertThrows(ReportQueryException.class, () -> ReportParameters.resolve(query(), Map.of()));
        assertEquals("PARAMETER_REQUIRED", error.errorCode());
    }

    private ReportSpec.Query query() {
        var query = new ReportSpec.Query();
        query.mode = ReportConstants.MODE_AGGREGATE;
        query.timeField = ReportConstants.TIME_FIELD_CREATED_AT;
        query.timezone = "UTC";
        query.bucket = ReportConstants.BUCKET_NONE;
        var timeRange = new ReportSpec.TimeRange();
        timeRange.param = "range";
        query.timeRange = timeRange;
        var filter = new ReportSpec.Filter();
        filter.field = "channel";
        filter.op = ReportConstants.FILTER_OP_IN;
        filter.param = "stores";
        query.filters = new java.util.ArrayList<>(List.of(filter));
        var measure = new ReportSpec.Measure();
        measure.id = "total";
        measure.field = "data.total";
        measure.op = ReportConstants.OP_SUM;
        query.measures = List.of(measure);
        return query;
    }

    private ReportSpec.TimeRange range() {
        var range = new ReportSpec.TimeRange();
        range.kind = ReportConstants.RANGE_LAST_N_COMPLETE_DAYS;
        range.days = 30;
        return range;
    }
}
