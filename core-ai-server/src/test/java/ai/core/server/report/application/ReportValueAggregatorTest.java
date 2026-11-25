package ai.core.server.report.application;

import ai.core.server.report.domain.ReportConstants;
import ai.core.server.report.domain.ReportSpec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportValueAggregatorTest {
    @Test
    void evaluatesAddRatioAndMovingAverageInOrder() {
        var spec = new ReportSpec();
        spec.query = new ReportSpec.Query();
        spec.query.measures = List.of(
                measure("positive", "data.positive_count"),
                measure("total", "data.total"));
        var summed = derived("summed", ReportConstants.OP_ADD);
        summed.inputs = List.of("positive", "total");
        var share = derived("share", ReportConstants.OP_RATIO);
        share.numerator = "summed";
        share.denominator = "total";
        var moving = derived("positive_ma2", ReportConstants.OP_MOVING_AVG);
        moving.input = "positive";
        moving.window = 2;
        spec.query.derivedMeasures = List.of(summed, share, moving);

        var measureIndex = new LinkedHashMap<String, Integer>();
        measureIndex.put("positive", 0);
        measureIndex.put("total", 1);
        var rows = ReportValueAggregator.values(spec, measureIndex,
                List.of(List.of(2, 5), List.of(8, 10), List.of(10, 20)), false, false);

        assertEquals(java.util.Arrays.asList("2", "5", "7", "1.4", null), rows.get(0));
        assertEquals(List.of("8", "10", "18", "1.8", "5"), rows.get(1));
        assertEquals(List.of("10", "20", "30", "1.5", "9"), rows.get(2));
    }

    @Test
    void keepsAxisColumnsBeforeMeasures() {
        var spec = new ReportSpec();
        spec.query = new ReportSpec.Query();
        spec.query.measures = List.of(measure("total", "data.total"));

        var measureIndex = new LinkedHashMap<String, Integer>();
        measureIndex.put("total", 1);
        var rows = ReportValueAggregator.values(spec, measureIndex,
                List.of(List.of("2026-11-16T16:00:00Z", 10)), true, false);

        assertEquals(List.of("2026-11-16T16:00:00Z", "10"), rows.get(0));
    }

    private ReportSpec.Measure measure(String id, String field) {
        var measure = new ReportSpec.Measure();
        measure.id = id;
        measure.field = field;
        measure.op = ReportConstants.OP_SUM;
        return measure;
    }

    private ReportSpec.DerivedMeasure derived(String id, String op) {
        var derived = new ReportSpec.DerivedMeasure();
        derived.id = id;
        derived.op = op;
        return derived;
    }
}
