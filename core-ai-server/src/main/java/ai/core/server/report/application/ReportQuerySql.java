package ai.core.server.report.application;

import ai.core.server.dataset.store.DatasetViewManager;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.SchemaFieldType;
import ai.core.server.report.domain.ReportConstants;
import ai.core.server.report.domain.ReportSpec;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compiles report query fragments against the generated typed dataset view. Identifiers come from the
 * view column map; range and filter values are always bound parameters.
 *
 * @author core-ai
 */
final class ReportQuerySql {
    static ViewLayout layout(DatasetViewManager viewManager, Dataset dataset) {
        var columns = new LinkedHashMap<String, DatasetViewManager.Column>();
        for (var column : viewManager.columns(dataset)) columns.put(column.sourceName(), column);
        return new ViewLayout(DatasetViewManager.qualifiedView(DatasetViewManager.viewName(dataset.id)), columns);
    }

    static TimeColumn timeColumn(ReportSpec spec, ViewLayout layout) {
        var field = spec.query.timeField;
        if (ReportConstants.TIME_FIELD_CREATED_AT.equals(field)) {
            return new TimeColumn(quote("created_at"), TimeType.TIMESTAMP, null);
        }
        if (ReportConstants.TIME_FIELD_RUN_STARTED_AT.equals(field)) {
            return new TimeColumn(quote("run_started_at"), TimeType.TIMESTAMP, null);
        }
        var name = ReportSpecFields.dataField(field);
        var column = name != null ? layout.columns().get(name) : null;
        if (column == null) throw new ReportQueryException("SOURCE_SCHEMA_CHANGED", "time field not found in dataset view: " + field);
        if (column.type() != SchemaFieldType.DATE && column.type() != SchemaFieldType.DATETIME) {
            throw new ReportQueryException("SOURCE_SCHEMA_CHANGED", "time field is not a DATE or DATETIME column: " + field);
        }
        var type = column.type() == SchemaFieldType.DATE ? TimeType.DATE : TimeType.TIMESTAMP;
        return new TimeColumn(quote(column.columnName()), type, name);
    }

    static Where where(ReportSpec spec, ZonedDateTime from, ZonedDateTime to, ViewLayout layout, TimeColumn time) {
        var params = new ArrayList<Object>();
        var where = new StringBuilder(64).append("dataset_id = ?");
        params.add(spec.source.datasetId);
        if (time.type() == TimeType.DATE) {
            var zone = ZoneId.of(spec.query.timezone);
            params.add(from.withZoneSameInstant(zone).toLocalDate().toString());
            params.add(to.withZoneSameInstant(zone).toLocalDate().toString());
            where.append(" AND ").append(time.expression()).append(" >= ?::date AND ")
                    .append(time.expression()).append(" < ?::date");
        } else {
            params.add(from);
            params.add(to);
            where.append(" AND ").append(time.expression()).append(" >= ? AND ")
                    .append(time.expression()).append(" < ?");
        }
        var filter = ReportFilterSql.sql(spec.query.filters, layout.columns(), params);
        if (filter != null) where.append(" AND ").append(filter);
        return new Where(where.toString(), params);
    }

    static String bucketExpression(ReportSpec spec, TimeColumn time, List<Object> params) {
        params.add(spec.query.bucket.toLowerCase(Locale.ROOT));
        params.add(spec.query.timezone);
        if (time.type() == TimeType.DATE) {
            return "(date_trunc(?::text, " + time.expression() + "::timestamp) AT TIME ZONE ?::text)";
        }
        return "date_trunc(?::text, " + time.expression() + ", ?::text)";
    }

    static String measureExpression(ReportSpec.Measure measure, TimeColumn time, ViewLayout layout) {
        var op = measure.op == null ? "" : measure.op.toUpperCase(Locale.ROOT);
        if (ReportConstants.OP_COUNT.equals(op)) return "count(*)";
        var field = ReportSpecFields.dataField(measure.field);
        var column = column(layout, field);
        if (ReportConstants.OP_LAST.equals(op)) {
            return "(array_agg(" + column + " ORDER BY " + time.expression() + " DESC, "
                    + quote("created_at") + " DESC, " + quote("id") + " DESC))[1]";
        }
        return op.toLowerCase(Locale.ROOT) + "(" + column + ")";
    }

    static String column(ViewLayout layout, String field) {
        var column = field != null ? layout.columns().get(field) : null;
        if (column == null) throw new ReportQueryException("SOURCE_SCHEMA_CHANGED", "field not found in dataset view: " + field);
        return DatasetViewManager.quoteIdentifier(column.columnName());
    }

    static String sourceTimeExpression(TimeColumn time, String timezone, List<Object> params) {
        if (time.type() == TimeType.DATE) {
            params.add(timezone);
            return "(max(" + time.expression() + ")::timestamp AT TIME ZONE ?::text)";
        }
        return "max(" + time.expression() + ")";
    }

    private static String quote(String identifier) {
        return DatasetViewManager.quoteIdentifier(identifier);
    }

    private ReportQuerySql() {
    }

    enum TimeType {
        TIMESTAMP,
        DATE
    }

    record TimeColumn(String expression, TimeType type, String field) {
    }

    record ViewLayout(String view, Map<String, DatasetViewManager.Column> columns) {
    }

    record Where(String sql, List<Object> params) {
    }
}
