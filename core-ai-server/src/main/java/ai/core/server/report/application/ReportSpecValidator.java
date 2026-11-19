package ai.core.server.report.application;

import ai.core.server.domain.Dataset;
import ai.core.server.domain.SchemaField;
import ai.core.server.domain.SchemaFieldType;
import ai.core.server.report.domain.ReportConstants;
import ai.core.server.report.domain.ReportDependency;
import ai.core.server.report.domain.ReportSpec;
import ai.core.utils.JsonUtil;
import core.framework.api.http.HTTPStatus;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates and normalizes spec_version=1 report definitions. The validator is a pure domain policy:
 * it receives the Dataset metadata and never queries records.
 *
 * @author core-ai
 */
public class ReportSpecValidator {
    public static final int MAX_SPEC_BYTES = 32 * 1024;
    public static final int MAX_FILTERS = 20;
    public static final int MAX_MEASURES = 8;
    public static final int MAX_SERIES = 8;
    public static final int MAX_RECORD_FIELDS = 20;
    public static final int MAX_TITLE_LENGTH = 120;
    public static final int MAX_DESCRIPTION_LENGTH = 2000;
    public static final int MAX_QUERY_DAYS = 366;

    public static String dataField(String field) {
        if (field == null || !field.startsWith("data.")) return null;
        var name = field.substring("data.".length());
        if (name.isBlank() || name.contains(".")) return null;
        return name;
    }

    public ValidatedSpec validate(String title, String description, String specJson, Dataset dataset) {
        var issues = new ArrayList<Issue>();
        if (title == null || title.isBlank()) issues.add(new Issue("TITLE_REQUIRED", "/title", "non-blank", "给报表一个标题"));
        else if (title.length() > MAX_TITLE_LENGTH) issues.add(new Issue("TITLE_TOO_LONG", "/title", "<= " + MAX_TITLE_LENGTH, "缩短标题"));
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            issues.add(new Issue("DESCRIPTION_TOO_LONG", "/description", "<= " + MAX_DESCRIPTION_LENGTH, "缩短描述"));
        }
        if (specJson == null || specJson.isBlank()) {
            issues.add(new Issue("SPEC_REQUIRED", "/", "spec_json", "提交 ReportSpec"));
            throw new ReportValidationException(issues);
        }
        if (specJson.getBytes(StandardCharsets.UTF_8).length > MAX_SPEC_BYTES) {
            issues.add(new Issue("SPEC_TOO_LARGE", "/", "<= " + MAX_SPEC_BYTES + " bytes", "缩小 spec"));
        }
        ReportSpec spec;
        try {
            spec = JsonUtil.fromJson(ReportSpec.class, specJson);
        } catch (RuntimeException | Error e) {
            issues.add(new Issue("SPEC_INVALID_JSON", "/", "valid spec_version=1 json", "检查 JSON 语法和字段类型"));
            throw new ReportValidationException(issues, e);
        }
        if (spec.specVersion == null || spec.specVersion != ReportConstants.SPEC_VERSION) {
            issues.add(new Issue("UNSUPPORTED_SPEC_VERSION", "/spec_version", String.valueOf(ReportConstants.SPEC_VERSION), "使用当前协议版本"));
        }
        if (spec.source == null || spec.source.datasetId == null || !spec.source.datasetId.equals(dataset.id)) {
            issues.add(new Issue("SOURCE_MISMATCH", "/source/dataset_id", dataset.id, "source 必须是当前 Dataset"));
        }
        if (spec.query == null) {
            issues.add(new Issue("QUERY_REQUIRED", "/query", "object", "补充 query"));
        } else {
            validateQuery(spec.query, dataset, issues);
        }
        if (spec.display == null) {
            issues.add(new Issue("DISPLAY_REQUIRED", "/display", "object", "补充 display"));
        }
        if (spec.query != null && spec.display != null) {
            validateDisplay(spec.query, spec.display, issues);
        }
        if (spec.freshness != null && spec.freshness.expectedIntervalHours != null
                && (spec.freshness.expectedIntervalHours <= 0 || spec.freshness.expectedIntervalHours > 24 * 366)) {
            issues.add(new Issue("INVALID_FRESHNESS_INTERVAL", "/freshness/expected_interval_hours", "1..8784", "给出合理的采集间隔"));
        }
        if (!issues.isEmpty()) throw new ReportValidationException(issues);

        var referenced = referencedFields(spec);
        var dependency = ReportDependency.of(dataset, referenced);
        return new ValidatedSpec(spec, JsonUtil.toJson(spec), dependency, referenced);
    }

    private void validateQuery(ReportSpec.Query query, Dataset dataset, List<Issue> issues) {
        if (query.mode == null || !Set.of(ReportConstants.MODE_AGGREGATE, ReportConstants.MODE_RECORDS).contains(query.mode)) {
            issues.add(new Issue("INVALID_QUERY_MODE", "/query/mode", "AGGREGATE|RECORDS", "选择查询模式"));
        }
        if (query.timeField == null || !Set.of(ReportConstants.TIME_FIELD_RUN_STARTED_AT, ReportConstants.TIME_FIELD_CREATED_AT).contains(query.timeField)) {
            issues.add(new Issue("INVALID_TIME_FIELD", "/query/time_field", "meta.run_started_at|meta.created_at", "使用允许的时间字段"));
        }
        if (query.timeRange == null) {
            issues.add(new Issue("TIME_RANGE_REQUIRED", "/query/time_range", "object", "补充时间范围"));
        } else {
            validateTimeRange(query.timeRange, issues);
        }
        validateTimezone(query.timezone, issues);
        if (query.bucket != null && !Set.of(ReportConstants.BUCKET_NONE, ReportConstants.BUCKET_HOUR, ReportConstants.BUCKET_DAY,
                ReportConstants.BUCKET_WEEK, ReportConstants.BUCKET_MONTH).contains(query.bucket)) {
            issues.add(new Issue("INVALID_BUCKET", "/query/bucket", "NONE|HOUR|DAY|WEEK|MONTH", "选择允许的粒度"));
        }
        var schema = schema(dataset);
        if (query.categoryField != null && !query.categoryField.isBlank()
                && (query.categoryField.contains(".") || !schema.containsKey(query.categoryField))) {
            issues.add(new Issue("UNKNOWN_CATEGORY_FIELD", "/query/category_field", "top-level schema field", "使用已声明的字段"));
        }
        validateFilters(query, schema, issues);
        if (ReportConstants.MODE_RECORDS.equals(query.mode)) {
            validateRecordFields(query, schema, issues);
        } else {
            validateMeasures(query, schema, issues);
        }
    }

    private void validateTimezone(String timezone, List<Issue> issues) {
        if (timezone == null || timezone.isBlank()) {
            issues.add(new Issue("TIMEZONE_REQUIRED", "/query/timezone", "IANA zone", "指定报表时区"));
            return;
        }
        try {
            ZoneId.of(timezone);
        } catch (RuntimeException e) {
            issues.add(new Issue("INVALID_TIMEZONE", "/query/timezone", "IANA zone", "检查时区"));
        }
    }

    private void validateFilters(ReportSpec.Query query, java.util.Map<String, SchemaFieldType> schema, List<Issue> issues) {
        if (query.filters == null) return;
        if (query.filters.size() > MAX_FILTERS) issues.add(new Issue("TOO_MANY_FILTERS", "/query/filters", "<= " + MAX_FILTERS, "减少过滤条件"));
        for (int i = 0; i < query.filters.size(); i++) {
            var filter = query.filters.get(i);
            var path = "/query/filters/" + i;
            if (filter == null || filter.field == null || filter.field.isBlank()) {
                issues.add(new Issue("FILTER_FIELD_REQUIRED", path + "/field", "schema field", "补充字段"));
            } else if (filter.field.contains(".") || !schema.containsKey(filter.field)) {
                issues.add(new Issue("UNKNOWN_FILTER_FIELD", path + "/field", "top-level schema field", "使用已声明的字段"));
            }
            if (filter != null && !isScalar(filter.value)) {
                issues.add(new Issue("INVALID_FILTER_VALUE", path + "/value", "string|number|boolean|null", "只支持等值标量"));
            }
        }
    }

    private void validateRecordFields(ReportSpec.Query query, java.util.Map<String, SchemaFieldType> schema, List<Issue> issues) {
        if (query.measures != null && !query.measures.isEmpty()) issues.add(new Issue("RECORDS_WITH_MEASURES", "/query/measures", "empty for RECORDS", "RECORDS 模式不要提交 measures"));
        if (query.derivedMeasures != null && !query.derivedMeasures.isEmpty()) issues.add(new Issue("RECORDS_WITH_DERIVED", "/query/derived_measures", "empty for RECORDS", "RECORDS 模式不要提交 derived_measures"));
        if (query.recordFields == null || query.recordFields.isEmpty()) {
            issues.add(new Issue("RECORD_FIELDS_REQUIRED", "/query/record_fields", "1.." + MAX_RECORD_FIELDS, "选择记录字段"));
            return;
        }
        if (query.recordFields.size() > MAX_RECORD_FIELDS) {
            issues.add(new Issue("TOO_MANY_RECORD_FIELDS", "/query/record_fields", "<= " + MAX_RECORD_FIELDS, "减少字段"));
            return;
        }
        for (var field : query.recordFields) {
            if (field == null || field.contains(".") || !schema.containsKey(field)) {
                issues.add(new Issue("UNKNOWN_RECORD_FIELD", "/query/record_fields", "top-level schema field", "使用已声明的字段: " + field));
            }
        }
    }

    private void validateMeasures(ReportSpec.Query query, java.util.Map<String, SchemaFieldType> schema, List<Issue> issues) {
        var ids = new HashSet<String>();
        int measureCount = query.measures != null ? query.measures.size() : 0;
        int derivedCount = query.derivedMeasures != null ? query.derivedMeasures.size() : 0;
        if (query.measures == null || query.measures.isEmpty()) issues.add(new Issue("MEASURES_REQUIRED", "/query/measures", "1.." + MAX_MEASURES, "至少一个 measure"));
        if (measureCount + derivedCount > MAX_MEASURES) {
            issues.add(new Issue("TOO_MANY_MEASURES", "/query/measures", "total <= " + MAX_MEASURES, "减少 measure/派生"));
        }
        validateBaseMeasures(query, schema, ids, issues);
        validateDerivedMeasures(query, ids, issues);
    }

    private void validateBaseMeasures(ReportSpec.Query query, java.util.Map<String, SchemaFieldType> schema,
                                      Set<String> ids, List<Issue> issues) {
        if (query.measures == null) return;
        for (int i = 0; i < query.measures.size(); i++) {
            var measure = query.measures.get(i);
            var path = "/query/measures/" + i;
            if (measure == null || measure.id == null || measure.id.isBlank()) {
                issues.add(new Issue("MEASURE_ID_REQUIRED", path + "/id", "unique id", "补充 id"));
                continue;
            }
            if (!ids.add(measure.id)) issues.add(new Issue("DUPLICATE_MEASURE_ID", path + "/id", "unique", "id 不能重复"));
            var op = measure.op == null ? "" : measure.op.toUpperCase(java.util.Locale.ROOT);
            if (!Set.of(ReportConstants.OP_SUM, ReportConstants.OP_AVG, ReportConstants.OP_MIN, ReportConstants.OP_MAX,
                    ReportConstants.OP_COUNT, ReportConstants.OP_LAST).contains(op)) {
                issues.add(new Issue("INVALID_MEASURE_OP", path + "/op", "SUM|AVG|MIN|MAX|COUNT|LAST", "选择支持的聚合"));
                continue;
            }
            if (ReportConstants.OP_COUNT.equals(op)) continue;
            var field = dataField(measure.field);
            if (field == null) {
                issues.add(new Issue("INVALID_MEASURE_FIELD", path + "/field", "data.<schema field>", "使用 data.<field>"));
                continue;
            }
            var type = schema.get(field);
            if (type == null) {
                issues.add(new Issue("UNKNOWN_MEASURE_FIELD", path + "/field", "schema field", "使用已声明的字段"));
            } else if (type != SchemaFieldType.NUMBER && !ReportConstants.OP_LAST.equals(op)) {
                issues.add(new Issue("MEASURE_REQUIRES_NUMBER", path + "/field", "NUMBER", op + " 需要数值字段"));
            }
        }
    }

    private void validateDerivedMeasures(ReportSpec.Query query, Set<String> ids, List<Issue> issues) {
        if (query.derivedMeasures == null) return;
        for (int i = 0; i < query.derivedMeasures.size(); i++) {
            var derived = query.derivedMeasures.get(i);
            var path = "/query/derived_measures/" + i;
            if (derived == null || derived.id == null || derived.id.isBlank()) {
                issues.add(new Issue("DERIVED_ID_REQUIRED", path + "/id", "unique id", "补充 id"));
                continue;
            }
            if (!ids.add(derived.id)) issues.add(new Issue("DUPLICATE_MEASURE_ID", path + "/id", "unique", "id 不能重复"));
            if (!ReportConstants.OP_RATIO.equalsIgnoreCase(derived.op)) {
                issues.add(new Issue("INVALID_DERIVED_OP", path + "/op", "RATIO", "首版只支持 RATIO"));
                continue;
            }
            if (derived.numerator == null || derived.denominator == null || derived.numerator.equals(derived.denominator)) {
                issues.add(new Issue("INVALID_RATIO", path, "two different base measure ids", "指定分子/分母"));
            } else if (!baseMeasureIds(query).contains(derived.numerator) || !baseMeasureIds(query).contains(derived.denominator)) {
                issues.add(new Issue("RATIO_REFERENCES_UNKNOWN", path, "base measure ids", "分子/分母必须是基础 measure"));
            }
        }
    }

    private void validateDisplay(ReportSpec.Query query, ReportSpec.Display display, List<Issue> issues) {
        var type = display.type;
        if (!Set.of(ReportConstants.DISPLAY_METRIC, ReportConstants.DISPLAY_LINE, ReportConstants.DISPLAY_BAR,
                ReportConstants.DISPLAY_TABLE).contains(type)) {
            issues.add(new Issue("INVALID_DISPLAY_TYPE", "/display/type", "METRIC|LINE|BAR|TABLE", "选择展示类型"));
            return;
        }
        boolean hasBucket = query.bucket != null && !ReportConstants.BUCKET_NONE.equals(query.bucket);
        boolean hasCategory = query.categoryField != null && !query.categoryField.isBlank();
        if (ReportConstants.DISPLAY_METRIC.equals(type)) {
            if (hasBucket || hasCategory) issues.add(new Issue("METRIC_SINGLE_VALUE", "/display/type", "bucket/category NONE", "METRIC 只能单值"));
            int measures = (query.measures != null ? query.measures.size() : 0) + (query.derivedMeasures != null ? query.derivedMeasures.size() : 0);
            if (measures != 1) issues.add(new Issue("METRIC_ONE_MEASURE", "/display/series", "exactly one measure", "METRIC 只展示一个指标"));
        } else if (ReportConstants.DISPLAY_LINE.equals(type) || ReportConstants.DISPLAY_BAR.equals(type)) {
            if (!hasBucket && !hasCategory) issues.add(new Issue("CHART_AXIS_REQUIRED", "/display/type", "bucket or category", "图表需要 x 轴"));
            if (hasBucket && hasCategory) issues.add(new Issue("CHART_AXIS_AMBIGUOUS", "/display/type", "bucket XOR category", "首版不同时使用 bucket 和 category"));
        } else if (ReportConstants.DISPLAY_TABLE.equals(type) && ReportConstants.MODE_RECORDS.equals(query.mode)) {
            return;
        }
        if (display.series != null) {
            var ids = new HashSet<>(baseMeasureIds(query));
            addDerivedIds(query, ids);
            if (display.series.size() > MAX_SERIES) issues.add(new Issue("TOO_MANY_SERIES", "/display/series", "<= " + MAX_SERIES, "减少 series"));
            for (int i = 0; i < display.series.size(); i++) {
                var series = display.series.get(i);
                if (series == null || series.field == null || !ids.contains(series.field)) {
                    issues.add(new Issue("UNKNOWN_SERIES_FIELD", "/display/series/" + i + "/field", "measure id", "引用已声明的 measure"));
                }
                if (series != null && series.format != null && !Set.of(ReportConstants.FORMAT_NUMBER, ReportConstants.FORMAT_PERCENT,
                        ReportConstants.FORMAT_CURRENCY).contains(series.format)) {
                    issues.add(new Issue("INVALID_FORMAT", "/display/series/" + i + "/format", "NUMBER|PERCENT|CURRENCY", "选择格式"));
                }
            }
        }
        if (display.precision != null && (display.precision < 0 || display.precision > 6)) {
            issues.add(new Issue("INVALID_PRECISION", "/display/precision", "0..6", "调整精度"));
        }
    }

    private void validateTimeRange(ReportSpec.TimeRange range, List<Issue> issues) {
        if (!Set.of(ReportConstants.RANGE_TODAY, ReportConstants.RANGE_YESTERDAY, ReportConstants.RANGE_LAST_N_COMPLETE_DAYS,
                ReportConstants.RANGE_ROLLING_DAYS, ReportConstants.RANGE_FIXED).contains(range.kind)) {
            issues.add(new Issue("INVALID_TIME_RANGE", "/query/time_range/kind", "TODAY|YESTERDAY|LAST_N_COMPLETE_DAYS|ROLLING_DAYS|FIXED", "选择时间范围"));
            return;
        }
        if ((ReportConstants.RANGE_LAST_N_COMPLETE_DAYS.equals(range.kind) || ReportConstants.RANGE_ROLLING_DAYS.equals(range.kind))
                && (range.days == null || range.days < 1 || range.days > MAX_QUERY_DAYS)) {
            issues.add(new Issue("INVALID_TIME_RANGE_DAYS", "/query/time_range/days", "1.." + MAX_QUERY_DAYS, "调整天数"));
        }
        if (ReportConstants.RANGE_FIXED.equals(range.kind)) {
            if (range.from == null || range.to == null) {
                issues.add(new Issue("FIXED_RANGE_REQUIRED", "/query/time_range", "from/to", "固定窗口需要 from/to"));
            } else {
                try {
                    java.time.Instant.parse(range.from);
                    java.time.Instant.parse(range.to);
                } catch (DateTimeParseException e) {
                    issues.add(new Issue("INVALID_FIXED_RANGE", "/query/time_range", "ISO instant", "使用 UTC instant"));
                }
            }
        }
    }

    private Set<String> referencedFields(ReportSpec spec) {
        var fields = new LinkedHashSet<String>();
        if (spec.query.categoryField != null) fields.add(spec.query.categoryField);
        if (spec.query.filters != null) for (var filter : spec.query.filters) if (filter != null && filter.field != null) fields.add(filter.field);
        if (spec.query.recordFields != null) fields.addAll(spec.query.recordFields);
        if (spec.query.measures != null) {
            for (var measure : spec.query.measures) {
                var field = dataField(measure != null ? measure.field : null);
                if (field != null) fields.add(field);
            }
        }
        return fields;
    }

    private void addDerivedIds(ReportSpec.Query query, Set<String> ids) {
        if (query.derivedMeasures == null) return;
        for (var derived : query.derivedMeasures) {
            if (derived != null && derived.id != null) ids.add(derived.id);
        }
    }

    private Set<String> baseMeasureIds(ReportSpec.Query query) {
        var ids = new HashSet<String>();
        if (query.measures != null) for (var measure : query.measures) if (measure != null && measure.id != null) ids.add(measure.id);
        return ids;
    }

    private java.util.Map<String, SchemaFieldType> schema(Dataset dataset) {
        var schema = new java.util.LinkedHashMap<String, SchemaFieldType>();
        if (dataset.schema != null) {
            for (SchemaField field : dataset.schema) {
                if (field != null && field.name != null) schema.put(field.name, field.type != null ? field.type : SchemaFieldType.STRING);
            }
        }
        return schema;
    }

    private boolean isScalar(Object value) {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    public record ValidatedSpec(ReportSpec spec, String specJson, ReportDependency dependency, Set<String> referencedFields) {
    }

    public record Issue(String code, String path, String expected, String suggestion) {
    }

    public static class ReportValidationException extends ReportException {
        private static final long serialVersionUID = 1L;
        private final transient List<Issue> issues;

        public ReportValidationException(List<Issue> issues) {
            super("REPORT_INVALID_SPEC", HTTPStatus.BAD_REQUEST,
                    issues.isEmpty() ? "invalid report spec" : issues.getFirst().code() + " at " + issues.getFirst().path(),
                    JsonUtil.toJson(java.util.Map.of("issues", issues)));
            this.issues = List.copyOf(issues);
        }

        public ReportValidationException(List<Issue> issues, Throwable cause) {
            super("REPORT_INVALID_SPEC", HTTPStatus.BAD_REQUEST,
                    issues.isEmpty() ? "invalid report spec" : issues.getFirst().code() + " at " + issues.getFirst().path(),
                    cause);
            this.issues = List.copyOf(issues);
        }

        public List<Issue> issues() {
            return issues;
        }
    }
}
