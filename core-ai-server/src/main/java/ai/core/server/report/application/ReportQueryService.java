package ai.core.server.report.application;

import ai.core.server.dataset.store.DatasetViewManager;
import ai.core.server.dataset.store.PgJsonRow;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.SchemaField;
import ai.core.server.report.domain.ReportConstants;
import ai.core.server.report.domain.ReportData;
import ai.core.server.report.domain.ReportSpec;
import ai.core.server.report.store.PgReportInvalidCountRow;
import ai.core.server.report.store.PgReportOptionRow;
import ai.core.server.report.store.PgReportRangeRow;
import ai.core.server.report.store.PgReportRecordRow;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Executes spec_version=1 report queries against the generated typed dataset view. All generated SQL uses bound
 * parameters except allowlisted view column names and function names.
 *
 * @author core-ai
 */
public class ReportQueryService {
    public static final int MAX_AGGREGATE_ROWS = 1000;
    public static final int MAX_CATEGORIES = 50;
    public static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private static <T> List<T> nullSafe(List<T> values) {
        return values != null ? values : List.of();
    }

    private static final TypeReference<List<Object>> ROW_TYPE = new TypeReference<>() { };

    @Inject
    Database database;

    @Inject
    DatasetViewManager viewManager;

    public ReportData query(QueryRequest request) {
        var layout = ReportQuerySql.layout(viewManager, request.dataset());
        var time = ReportQuerySql.timeColumn(request.spec(), layout);
        var range = ReportRangeResolver.resolve(new ReportRangeResolver.Context(request.spec(), request.dataset(),
                request.evaluatedAt(), request.override(), layout, time, database));
        var data = ReportConstants.MODE_RECORDS.equals(request.spec().query.mode)
                ? records(request, range, layout, time)
                : aggregate(request, range, layout, time);
        data.freshnessStatus = freshness(request.spec(), range, data);
        if (JsonUtil.toJson(data.rows).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            throw new ReportQueryException("RESULT_LIMIT_EXCEEDED", "report result exceeds 256 KiB");
        }
        return data;
    }

    public ResolvedRange resolveRange(ReportSpec spec, ZonedDateTime evaluatedAt, RangeOverride override) {
        return ReportRangeResolver.resolve(new ReportRangeResolver.Context(spec, null, evaluatedAt, override, null, null, database));
    }

    private ReportData aggregate(QueryRequest request, ResolvedRange range, ReportQuerySql.ViewLayout layout, ReportQuerySql.TimeColumn time) {
        var spec = request.spec();
        var result = aggregateRows(spec, range, layout, time);
        var data = new ReportData();
        data.reportId = request.reportId();
        data.reportRevision = request.reportRevision();
        data.specVersion = request.specVersion();
        data.fromInclusive = instant(range.from());
        data.toExclusive = instant(range.to());
        data.timezone = spec.query.timezone;
        data.columns = result.columns();
        data.rows = result.rows();
        var meta = metadata(spec, range, layout, time);
        data.matchedRecordCount = meta.matched();
        data.sourceTime = meta.sourceTime() != null ? meta.sourceTime().toInstant().toString() : null;
        data.sourceUpdatedAt = meta.sourceUpdatedAt() != null ? meta.sourceUpdatedAt().toInstant().toString() : null;
        data.invalidValueCount = invalidCount(spec, range, layout, time);
        data.status = meta.matched() == 0 ? ReportConstants.STATUS_NO_DATA : ReportConstants.STATUS_READY;
        data.evaluatedAt = instant(range.evaluatedAt());
        data.hasMore = false;
        return data;
    }

    private AggregateResult aggregateRows(ReportSpec spec, ResolvedRange range, ReportQuerySql.ViewLayout layout, ReportQuerySql.TimeColumn time) {
        var aggregate = aggregateSql(spec, range, layout, time);
        var rawRows = database.select(aggregate.sql(), PgJsonRow.class, aggregate.params().toArray());
        if (rawRows.size() > MAX_AGGREGATE_ROWS) {
            throw new ReportQueryException("RESULT_LIMIT_EXCEEDED", "aggregate rows exceed " + MAX_AGGREGATE_ROWS);
        }
        if (spec.query.limit == null && hasCategory(spec) && rawRows.size() > MAX_CATEGORIES) {
            throw new ReportQueryException("RESULT_LIMIT_EXCEEDED", "categories exceed " + MAX_CATEGORIES);
        }
        var parsed = new ArrayList<List<Object>>(rawRows.size());
        for (var rowJson : rawRows) parsed.add(JsonUtil.fromJson(ROW_TYPE, rowJson.payload));
        return new AggregateResult(aggregate.columns(),
                ReportValueAggregator.values(spec, aggregate.measureIndex(), parsed, hasBucket(spec), hasCategory(spec)));
    }

    private AggregateSql aggregateSql(ReportSpec spec, ResolvedRange range, ReportQuerySql.ViewLayout layout, ReportQuerySql.TimeColumn time) {
        var where = ReportQuerySql.where(spec, range.from(), range.to(), layout, time);
        var select = selectParts(spec, layout, time);
        var params = new ArrayList<Object>(select.params());
        params.addAll(where.params());
        var sql = new StringBuilder(512).append("SELECT jsonb_build_array(");
        for (int i = 0; i < select.selects().size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("t.c").append(i);
        }
        sql.append(")::text AS payload FROM (SELECT ");
        for (int i = 0; i < select.selects().size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(select.selects().get(i)).append(" AS c").append(i);
        }
        sql.append(" FROM ").append(layout.view()).append(" WHERE ").append(where.sql());
        if (!select.groupBy().isEmpty()) sql.append(" GROUP BY ").append(String.join(", ", select.groupBy()));
        if (!select.groupBy().isEmpty()) {
            var orderBy = spec.query.orderBy;
            var orderIndex = orderBy != null ? select.measureIndex().get(orderBy.measure) : null;
            if (orderIndex != null && hasCategory(spec)) {
                var direction = orderBy.direction == null || orderBy.direction.isBlank() ? "DESC" : orderBy.direction;
                sql.append(" ORDER BY c").append(orderIndex).append(' ').append(direction);
            } else {
                sql.append(" ORDER BY ").append(String.join(", ", select.groupBy()));
            }
        }
        int limit = spec.query.limit != null && hasCategory(spec) ? spec.query.limit : MAX_AGGREGATE_ROWS + 1;
        sql.append(" LIMIT ").append(limit).append(") t");
        return new AggregateSql(sql.toString(), params, select.columns(), select.measureIndex());
    }

    private SelectParts selectParts(ReportSpec spec, ReportQuerySql.ViewLayout layout, ReportQuerySql.TimeColumn time) {
        var columns = new ArrayList<ReportData.Column>();
        var selects = new ArrayList<String>();
        var params = new ArrayList<Object>();
        var groupBy = new ArrayList<String>();
        if (hasBucket(spec)) {
            selects.add(ReportQuerySql.bucketExpression(spec, time, params));
            groupBy.add(String.valueOf(selects.size()));
            columns.add(new ReportData.Column("bucket", "TIME"));
        }
        if (hasCategory(spec)) {
            selects.add(ReportQuerySql.column(layout, spec.query.categoryField));
            groupBy.add(String.valueOf(selects.size()));
            columns.add(new ReportData.Column("category", "STRING"));
        }
        var measureIndex = new LinkedHashMap<String, Integer>();
        for (var measure : spec.query.measures) {
            selects.add(ReportQuerySql.measureExpression(measure, time, layout));
            measureIndex.put(measure.id, selects.size() - 1);
            columns.add(new ReportData.Column(measure.id, measureType(measure), measure.label));
        }
        for (var derived : nullSafe(spec.query.derivedMeasures)) {
            columns.add(new ReportData.Column(derived.id, "DECIMAL", derived.label));
        }
        return new SelectParts(columns, selects, params, groupBy, measureIndex);
    }

    private String measureType(ReportSpec.Measure measure) {
        if (ReportConstants.OP_LAST.equalsIgnoreCase(measure.op)) return "STRING";
        return "DECIMAL";
    }

    private ReportData records(QueryRequest request, ResolvedRange range, ReportQuerySql.ViewLayout layout, ReportQuerySql.TimeColumn time) {
        var spec = request.spec();
        var where = ReportQuerySql.where(spec, range.from(), range.to(), layout, time);
        var fields = recordFields(spec, request.dataset());
        var params = new ArrayList<Object>(where.params());
        int limit = Math.min(Math.max(request.pageSize(), 1), 200);
        int offset = Math.max(request.offset(), 0);
        params.add(limit);
        params.add(offset);
        var rows = database.select("SELECT id, run_started_at, created_at, data::text AS data FROM " + layout.view()
                        + " WHERE " + where.sql() + " ORDER BY " + time.expression() + " DESC, "
                        + quote("created_at") + " DESC, " + quote("id") + " DESC LIMIT ? OFFSET ?",
                PgReportRecordRow.class, params.toArray());

        var data = new ReportData();
        data.reportId = request.reportId();
        data.reportRevision = request.reportRevision();
        data.specVersion = request.specVersion();
        data.fromInclusive = instant(range.from());
        data.toExclusive = instant(range.to());
        data.timezone = spec.query.timezone;
        for (var field : fields) {
            var schemaField = schemaField(request.dataset(), field);
            data.columns.add(new ReportData.Column(field, schemaField != null && schemaField.type != null ? schemaField.type.name() : "STRING"));
        }
        var total = metadata(spec, range, layout, time);
        for (var row : rows) {
            Map<String, Object> recordData;
            try {
                recordData = row.data == null || row.data.isBlank() ? Map.of() : JsonUtil.toMap(row.data);
            } catch (RuntimeException e) {
                recordData = Map.of();
            }
            var values = new ArrayList<String>(fields.size());
            for (var field : fields) values.add(ReportValueFormatter.value(recordData.get(field), null));
            data.rows.add(values);
        }
        data.matchedRecordCount = total.matched();
        data.sourceTime = total.sourceTime() != null ? total.sourceTime().toInstant().toString() : null;
        data.sourceUpdatedAt = total.sourceUpdatedAt() != null ? total.sourceUpdatedAt().toInstant().toString() : null;
        data.invalidValueCount = 0;
        data.status = rows.isEmpty() ? ReportConstants.STATUS_NO_DATA : ReportConstants.STATUS_READY;
        data.evaluatedAt = instant(range.evaluatedAt());
        data.hasMore = (long) offset + rows.size() < total.matched();
        return data;
    }

    private ReportMetadata metadata(ReportSpec spec, ResolvedRange range, ReportQuerySql.ViewLayout layout, ReportQuerySql.TimeColumn time) {
        var where = ReportQuerySql.where(spec, range.from(), range.to(), layout, time);
        var params = new ArrayList<Object>();
        var sourceTime = ReportQuerySql.sourceTimeExpression(time, spec.query.timezone, params);
        params.addAll(where.params());
        var row = database.selectOne("SELECT count(*) AS matched, " + sourceTime + " AS source_time, "
                        + "max(" + quote("updated_at") + ") AS source_updated_at FROM " + layout.view()
                        + " WHERE " + where.sql(),
                PgReportRangeRow.class, params.toArray());
        return row.map(value -> new ReportMetadata(value.matched != null ? value.matched : 0, value.sourceTime, value.sourceUpdatedAt))
                .orElse(new ReportMetadata(0, null, null));
    }

    private long invalidCount(ReportSpec spec, ResolvedRange range, ReportQuerySql.ViewLayout layout, ReportQuerySql.TimeColumn time) {
        var fields = new LinkedHashSet<String>();
        for (var measure : nullSafe(spec.query.measures)) {
            if (measure != null && !ReportConstants.OP_COUNT.equalsIgnoreCase(measure.op)
                    && !ReportConstants.OP_COUNT_DISTINCT.equalsIgnoreCase(measure.op)
                    && !ReportConstants.OP_LAST.equalsIgnoreCase(measure.op)) {
                var field = ReportSpecFields.dataField(measure.field);
                if (field != null) fields.add(field);
            }
        }
        if (fields.isEmpty()) return 0;
        var where = ReportQuerySql.where(spec, range.from(), range.to(), layout, time);
        var params = new ArrayList<Object>(where.params());
        var conditions = new ArrayList<String>();
        for (var field : fields) {
            var column = layout.columns().get(field);
            if (column == null) continue;
            params.add(field);
            conditions.add("(data -> ? IS NOT NULL AND " + DatasetViewManager.quoteIdentifier(column.columnName()) + " IS NULL)");
        }
        if (conditions.isEmpty()) return 0;
        var row = database.selectOne("SELECT count(DISTINCT id) AS invalid FROM " + layout.view()
                        + " WHERE " + where.sql() + " AND (" + String.join(" OR ", conditions) + ")",
                PgReportInvalidCountRow.class, params.toArray());
        return row.map(value -> value.invalid != null ? value.invalid : 0L).orElse(0L);
    }

    public Map<String, List<String>> parameterOptions(ReportSpec spec, Dataset dataset) {
        var options = new LinkedHashMap<String, List<String>>();
        if (spec.parameters == null || spec.parameters.isEmpty() || dataset == null) return options;
        var layout = ReportQuerySql.layout(viewManager, dataset);
        for (var parameter : spec.parameters) {
            if (parameter == null || parameter.name == null || parameter.optionsField == null || parameter.optionsField.isBlank()) continue;
            var column = layout.columns().get(parameter.optionsField);
            if (column == null) continue;
            var ref = DatasetViewManager.quoteIdentifier(column.columnName());
            var rows = database.select("SELECT DISTINCT " + ref + "::text AS option_value FROM " + layout.view()
                            + " WHERE dataset_id = ? AND " + ref + " IS NOT NULL ORDER BY option_value LIMIT 200",
                    PgReportOptionRow.class, spec.source.datasetId);
            var values = rows.stream().map(row -> row.value).filter(value -> value != null).toList();
            if (!values.isEmpty()) options.put(parameter.name, values);
        }
        return options;
    }

    private boolean hasBucket(ReportSpec spec) {
        return spec.query.bucket != null && !ReportConstants.BUCKET_NONE.equals(spec.query.bucket);
    }

    private boolean hasCategory(ReportSpec spec) {
        return spec.query.categoryField != null && !spec.query.categoryField.isBlank();
    }

    private String quote(String identifier) {
        return DatasetViewManager.quoteIdentifier(identifier);
    }

    private List<String> recordFields(ReportSpec spec, Dataset dataset) {
        if (spec.query.recordFields != null && !spec.query.recordFields.isEmpty()) return spec.query.recordFields;
        var fields = new ArrayList<String>();
        if (dataset.schema != null) {
            for (var field : dataset.schema) {
                if (field != null && field.name != null && fields.size() < 20) fields.add(field.name);
            }
        }
        return fields;
    }

    private SchemaField schemaField(Dataset dataset, String name) {
        if (dataset.schema == null) return null;
        for (var field : dataset.schema) if (field != null && name.equals(field.name)) return field;
        return null;
    }

    private String freshness(ReportSpec spec, ResolvedRange range, ReportData data) {
        if (spec.freshness == null || spec.freshness.expectedIntervalHours == null || data.sourceTime == null) {
            return ReportConstants.FRESHNESS_UNKNOWN;
        }
        if (!ReportConstants.RANGE_TODAY.equals(spec.query.timeRange.kind)
                && !ReportConstants.RANGE_ROLLING_DAYS.equals(spec.query.timeRange.kind)) {
            return ReportConstants.FRESHNESS_UNKNOWN;
        }
        var source = Instant.parse(data.sourceTime);
        var delay = Duration.between(source, range.evaluatedAt().toInstant());
        return delay.compareTo(Duration.ofHours(spec.freshness.expectedIntervalHours)) > 0
                ? ReportConstants.FRESHNESS_DELAYED : ReportConstants.FRESHNESS_WITHIN_EXPECTATION;
    }

    private String instant(ZonedDateTime value) {
        return value.toInstant().toString();
    }

    private record ReportMetadata(long matched, ZonedDateTime sourceTime, ZonedDateTime sourceUpdatedAt) {
    }



    private record AggregateResult(List<ReportData.Column> columns, List<List<String>> rows) {
    }

    private record AggregateSql(String sql, List<Object> params, List<ReportData.Column> columns,
                                Map<String, Integer> measureIndex) {
    }

    private record SelectParts(List<ReportData.Column> columns, List<String> selects, List<Object> params,
                               List<String> groupBy, Map<String, Integer> measureIndex) {
    }

    public record ResolvedRange(ZonedDateTime from, ZonedDateTime to, ZonedDateTime evaluatedAt) {
    }

    public record RangeOverride(String kind, Integer days, String from, String to) {
    }

    public record QueryRequest(String reportId, long reportRevision, int specVersion, ReportSpec spec, Dataset dataset,
                               ZonedDateTime evaluatedAt, RangeOverride override, int pageSize, int offset) {
    }
}

