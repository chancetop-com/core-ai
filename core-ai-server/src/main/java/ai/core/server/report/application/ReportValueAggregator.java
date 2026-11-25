package ai.core.server.report.application;

import ai.core.server.report.domain.ReportConstants;
import ai.core.server.report.domain.ReportSpec;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds report rows from the raw aggregate columns. Base measures come from SQL; derived measures are
 * evaluated here in declaration order so the database only has to aggregate single fields.
 *
 * @author core-ai
 */
final class ReportValueAggregator {
    static List<List<String>> values(ReportSpec spec, Map<String, Integer> measureIndex,
                                     List<List<Object>> rawRows, boolean hasBucket, boolean hasCategory) {
        var baseDecimals = new LinkedHashMap<String, List<BigDecimal>>();
        var baseFormatted = new LinkedHashMap<String, List<String>>();
        for (var measure : nullSafe(spec.query.measures)) {
            baseDecimals.put(measure.id, new ArrayList<>(rawRows.size()));
            baseFormatted.put(measure.id, new ArrayList<>(rawRows.size()));
        }
        for (var raw : rawRows) {
            for (var measure : nullSafe(spec.query.measures)) {
                var index = measureIndex.get(measure.id);
                if (index == null) continue;
                baseDecimals.get(measure.id).add(ReportValueFormatter.decimal(raw.get(index)));
                baseFormatted.get(measure.id).add(ReportValueFormatter.value(raw.get(index), measure.op));
            }
        }
        var derived = evaluateDerived(spec, rawRows.size(), baseDecimals);
        int axisCount = (hasBucket ? 1 : 0) + (hasCategory ? 1 : 0);
        var rows = new ArrayList<List<String>>(rawRows.size());
        for (int i = 0; i < rawRows.size(); i++) {
            var row = new ArrayList<String>();
            for (int j = 0; j < axisCount; j++) row.add(ReportValueFormatter.value(rawRows.get(i).get(j), null));
            for (var measure : nullSafe(spec.query.measures)) row.add(baseFormatted.get(measure.id).get(i));
            for (var measure : nullSafe(spec.query.derivedMeasures)) row.add(derived.formatted().get(measure.id).get(i));
            rows.add(row);
        }
        return rows;
    }

    private static DerivedValues evaluateDerived(ReportSpec spec, int rowCount,
                                                 Map<String, List<BigDecimal>> baseDecimals) {
        var decimals = new LinkedHashMap<String, List<BigDecimal>>();
        var formatted = new LinkedHashMap<String, List<String>>();
        for (var derived : nullSafe(spec.query.derivedMeasures)) {
            if (ReportConstants.OP_MOVING_AVG.equalsIgnoreCase(derived.op)) continue;
            var values = new ArrayList<BigDecimal>(rowCount);
            for (int i = 0; i < rowCount; i++) {
                values.add(derivedValue(derived, i, baseDecimals, decimals));
            }
            decimals.put(derived.id, values);
            formatted.put(derived.id, format(values));
        }
        for (var derived : nullSafe(spec.query.derivedMeasures)) {
            if (!ReportConstants.OP_MOVING_AVG.equalsIgnoreCase(derived.op)) continue;
            var values = movingAverage(valueList(derived.input, baseDecimals, decimals),
                    derived.window != null ? derived.window : 7, rowCount);
            decimals.put(derived.id, values);
            formatted.put(derived.id, format(values));
        }
        return new DerivedValues(decimals, formatted);
    }

    private static List<BigDecimal> movingAverage(List<BigDecimal> inputs, int window, int rowCount) {
        var values = new ArrayList<BigDecimal>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            values.add(i < window - 1 || inputs == null ? null : average(inputs, window, i));
        }
        return values;
    }

    private static BigDecimal average(List<BigDecimal> inputs, int window, int rowIndex) {
        var sum = BigDecimal.ZERO;
        for (int j = rowIndex - window + 1; j <= rowIndex; j++) {
            var item = inputs.get(j);
            if (item == null) return null;
            sum = sum.add(item);
        }
        return sum.divide(BigDecimal.valueOf(window), MathContext.DECIMAL64);
    }

    private static BigDecimal derivedValue(ReportSpec.DerivedMeasure derived, int row,
                                           Map<String, List<BigDecimal>> baseDecimals,
                                           Map<String, List<BigDecimal>> derivedDecimals) {
        if (ReportConstants.OP_RATIO.equalsIgnoreCase(derived.op)) {
            var numerator = valueAt(derived.numerator, row, baseDecimals, derivedDecimals);
            var denominator = valueAt(derived.denominator, row, baseDecimals, derivedDecimals);
            if (numerator == null || denominator == null || denominator.signum() == 0) return null;
            return numerator.divide(denominator, MathContext.DECIMAL64);
        }
        if (ReportConstants.OP_ADD.equalsIgnoreCase(derived.op)) {
            var sum = BigDecimal.ZERO;
            for (var input : nullSafe(derived.inputs)) {
                var value = valueAt(input, row, baseDecimals, derivedDecimals);
                if (value == null) return null;
                sum = sum.add(value);
            }
            return sum;
        }
        return null;
    }

    private static BigDecimal valueAt(String id, int row, Map<String, List<BigDecimal>> baseDecimals,
                                      Map<String, List<BigDecimal>> derivedDecimals) {
        var values = valueList(id, baseDecimals, derivedDecimals);
        return values != null && row < values.size() ? values.get(row) : null;
    }

    private static List<BigDecimal> valueList(String id, Map<String, List<BigDecimal>> baseDecimals,
                                              Map<String, List<BigDecimal>> derivedDecimals) {
        if (id == null) return null;
        var values = baseDecimals.get(id);
        return values != null ? values : derivedDecimals.get(id);
    }

    private static List<String> format(List<BigDecimal> values) {
        var formatted = new ArrayList<String>(values.size());
        for (var value : values) formatted.add(value != null ? value.stripTrailingZeros().toPlainString() : null);
        return formatted;
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values != null ? values : List.of();
    }

    private ReportValueAggregator() {
    }

    private record DerivedValues(Map<String, List<BigDecimal>> decimals, Map<String, List<String>> formatted) {
    }
}
