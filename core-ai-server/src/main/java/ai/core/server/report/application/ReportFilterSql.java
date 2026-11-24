package ai.core.server.report.application;

import ai.core.server.dataset.store.DatasetViewManager;
import ai.core.server.domain.SchemaFieldType;
import ai.core.server.report.domain.ReportConstants;
import ai.core.server.report.domain.ReportSpec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Compiles typed report filters into predicates over the generated dataset view. Every referenced
 * column comes from the view's column map; values are always bound parameters.
 *
 * @author core-ai
 */
final class ReportFilterSql {
    static String sql(List<ReportSpec.Filter> filters, Map<String, DatasetViewManager.Column> columns, List<Object> params) {
        if (filters == null || filters.isEmpty()) return null;
        var parts = new ArrayList<String>();
        for (var filter : filters) {
            if (filter == null || filter.field == null) continue;
            var column = columns.get(filter.field);
            if (column == null) throw new ReportQueryException("SOURCE_SCHEMA_CHANGED", "field not found in dataset view: " + filter.field);
            parts.add(condition(column, filter.op, filter.value, params));
        }
        return parts.isEmpty() ? null : String.join(" AND ", parts);
    }

    private static String condition(DatasetViewManager.Column column, String op, Object value, List<Object> params) {
        var ref = DatasetViewManager.quoteIdentifier(column.columnName());
        var normalizedOp = op == null ? ReportConstants.FILTER_OP_EQ : op;
        if (ReportConstants.FILTER_OP_IN.equals(normalizedOp)) {
            var values = (List<?>) value;
            var placeholders = new ArrayList<String>(values.size());
            for (var item : values) placeholders.add(placeholder(column.type(), item, params));
            return ref + " IN (" + String.join(", ", placeholders) + ")";
        }
        if (value == null) {
            return ReportConstants.FILTER_OP_NE.equals(normalizedOp) ? ref + " IS NOT NULL" : ref + " IS NULL";
        }
        var placeholder = placeholder(column.type(), value, params);
        return switch (normalizedOp) {
            case ReportConstants.FILTER_OP_EQ -> ref + " = " + placeholder;
            case ReportConstants.FILTER_OP_NE -> ref + " IS DISTINCT FROM " + placeholder;
            case ReportConstants.FILTER_OP_GT -> ref + " > " + placeholder;
            case ReportConstants.FILTER_OP_GTE -> ref + " >= " + placeholder;
            case ReportConstants.FILTER_OP_LT -> ref + " < " + placeholder;
            case ReportConstants.FILTER_OP_LTE -> ref + " <= " + placeholder;
            default -> throw new ReportQueryException("INVALID_FILTER_OP", "unsupported filter op: " + normalizedOp);
        };
    }

    private static String placeholder(SchemaFieldType type, Object value, List<Object> params) {
        return switch (type != null ? type : SchemaFieldType.STRING) {
            case NUMBER -> {
                params.add(new BigDecimal(value.toString()));
                yield "?::numeric";
            }
            case BOOLEAN -> {
                params.add(value);
                yield "?";
            }
            case STRING -> {
                params.add(value);
                yield "?::text";
            }
            case DATE -> {
                params.add(value);
                yield "?::date";
            }
            case DATETIME -> {
                params.add(value);
                yield "?::timestamptz";
            }
        };
    }

    private ReportFilterSql() {
    }
}
