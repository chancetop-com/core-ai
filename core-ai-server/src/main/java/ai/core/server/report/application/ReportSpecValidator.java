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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    public static final int MAX_TOP_N = 50;
    public static final int MAX_RECORD_FIELDS = 20;
    public static final int MAX_TITLE_LENGTH = 120;
    public static final int MAX_DESCRIPTION_LENGTH = 2000;
    public static final int MAX_QUERY_DAYS = 366;
    private static final Set<String> FILTER_OPS = Set.of(ReportConstants.FILTER_OP_EQ, ReportConstants.FILTER_OP_NE, ReportConstants.FILTER_OP_GT, ReportConstants.FILTER_OP_GTE,
            ReportConstants.FILTER_OP_LT, ReportConstants.FILTER_OP_LTE, ReportConstants.FILTER_OP_IN);
    private static final ReportSpecStructureValidator STRUCTURE = ReportSpecStructureValidator.get();

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
        var parameters = STRUCTURE.parameters(spec, issues);
        var schema = schema(dataset);
        validateParameterOptions(spec, schema, issues);
        var components = STRUCTURE.components(spec, issues);
        validateComponents(components, dataset, parameters, issues);
        if (components.isEmpty() && spec.query == null) {
            issues.add(new Issue("QUERY_REQUIRED", "/query", "object", "补充 query 或 components"));
        }
        validateFreshness(spec, issues);
        if (!issues.isEmpty()) throw new ReportValidationException(issues);

        var referenced = referencedFields(components);
        addParameterOptionFields(spec, referenced);
        var dependency = ReportDependency.of(dataset, referenced);
        return new ValidatedSpec(spec, JsonUtil.toJson(spec), dependency, referenced);
    }

    private void validateComponents(List<ReportSpec.Component> components, Dataset dataset,
                                    Map<String, ReportSpec.Parameter> parameters, List<Issue> issues) {
        for (var component : components) {
            if (component.query == null) {
                issues.add(new Issue("QUERY_REQUIRED", "/query", "object", "补充 query"));
                continue;
            }
            if (component.display == null) {
                issues.add(new Issue("DISPLAY_REQUIRED", "/display", "object", "补充 display"));
                continue;
            }
            validateQuery(component.query, dataset, parameters, issues);
            STRUCTURE.validateDisplay(component.query, component.display, issues);
        }
    }

    private void validateParameterOptions(ReportSpec spec, Map<String, SchemaFieldType> schema, List<Issue> issues) {
        if (spec.parameters == null) return;
        for (int i = 0; i < spec.parameters.size(); i++) {
            var parameter = spec.parameters.get(i);
            if (parameter == null || parameter.optionsField == null || parameter.optionsField.isBlank()) continue;
            var field = ReportSpecFields.recordField(parameter.optionsField);
            if (field == null || !schema.containsKey(field)) {
                issues.add(new Issue("UNKNOWN_PARAMETER_OPTIONS_FIELD", "/parameters/" + i + "/options_field",
                        "top-level schema field", "使用已声明的字段"));
            } else {
                parameter.optionsField = field;
            }
        }
    }

    private void addParameterOptionFields(ReportSpec spec, Set<String> fields) {
        if (spec.parameters == null) return;
        for (var parameter : spec.parameters) {
            if (parameter != null && parameter.optionsField != null && !parameter.optionsField.isBlank()) {
                fields.add(parameter.optionsField);
            }
        }
    }

    private void validateFreshness(ReportSpec spec, List<Issue> issues) {
        if (spec.freshness != null && spec.freshness.expectedIntervalHours != null
                && (spec.freshness.expectedIntervalHours <= 0 || spec.freshness.expectedIntervalHours > 24 * 366)) {
            issues.add(new Issue("INVALID_FRESHNESS_INTERVAL", "/freshness/expected_interval_hours", "1..8784", "给出合理的采集间隔"));
        }
    }

    private void validateQuery(ReportSpec.Query query, Dataset dataset, Map<String, ReportSpec.Parameter> parameters, List<Issue> issues) {
        if (query.mode == null || !Set.of(ReportConstants.MODE_AGGREGATE, ReportConstants.MODE_RECORDS).contains(query.mode)) {
            issues.add(new Issue("INVALID_QUERY_MODE", "/query/mode", "AGGREGATE|RECORDS", "选择查询模式"));
        }
        var schema = schema(dataset);
        var timeType = validateTimeField(query, schema, issues);
        if (query.timeRange == null) {
            issues.add(new Issue("TIME_RANGE_REQUIRED", "/query/time_range", "object", "补充时间范围"));
        } else {
            STRUCTURE.validateTimeRange(query.timeRange, parameters, issues);
        }
        STRUCTURE.validateTimezone(query.timezone, issues);
        if (query.bucket != null && !Set.of(ReportConstants.BUCKET_NONE, ReportConstants.BUCKET_HOUR, ReportConstants.BUCKET_DAY,
                ReportConstants.BUCKET_WEEK, ReportConstants.BUCKET_MONTH).contains(query.bucket)) {
            issues.add(new Issue("INVALID_BUCKET", "/query/bucket", "NONE|HOUR|DAY|WEEK|MONTH", "选择允许的粒度"));
        } else if (timeType == SchemaFieldType.DATE && ReportConstants.BUCKET_HOUR.equals(query.bucket)) {
            issues.add(new Issue("INVALID_BUCKET", "/query/bucket", "NONE|DAY|WEEK|MONTH", "DATE 时间字段不支持 HOUR"));
        }
        if (query.categoryField != null && !query.categoryField.isBlank()) {
            var field = ReportSpecFields.recordField(query.categoryField);
            if (field == null || !schema.containsKey(field)) {
                issues.add(new Issue("UNKNOWN_CATEGORY_FIELD", "/query/category_field", "top-level schema field", "使用已声明的字段"));
            } else {
                query.categoryField = field;
            }
        }
        validateFilters(query, schema, parameters, issues);
        if (ReportConstants.MODE_RECORDS.equals(query.mode)) {
            validateRecordFields(query, schema, issues);
        } else {
            validateMeasures(query, schema, issues);
            validateOrderAndLimit(query, issues);
        }
    }

    private void validateOrderAndLimit(ReportSpec.Query query, List<Issue> issues) {
        boolean hasCategory = query.categoryField != null && !query.categoryField.isBlank();
        if (query.orderBy != null) {
            if (!hasCategory || query.bucket != null && !ReportConstants.BUCKET_NONE.equals(query.bucket)) {
                issues.add(new Issue("ORDER_BY_REQUIRES_CATEGORY", "/query/order_by", "category only", "排序只用于 category 维度"));
            }
            if (query.orderBy.measure == null || !baseMeasureIds(query).contains(query.orderBy.measure)) {
                issues.add(new Issue("ORDER_BY_UNKNOWN_MEASURE", "/query/order_by/measure", "base measure id", "排序 measure 必须是基础指标"));
            }
            var direction = query.orderBy.direction == null || query.orderBy.direction.isBlank()
                    ? "DESC" : query.orderBy.direction.toUpperCase(Locale.ROOT);
            if (!Set.of("ASC", "DESC").contains(direction)) {
                issues.add(new Issue("INVALID_ORDER_DIRECTION", "/query/order_by/direction", "ASC|DESC", "选择排序方向"));
            }
            query.orderBy.direction = direction;
        }
        if (query.limit != null) {
            if (!hasCategory) {
                issues.add(new Issue("LIMIT_REQUIRES_CATEGORY", "/query/limit", "category only", "limit 只用于 category 维度"));
            }
            if (query.limit < 1 || query.limit > MAX_TOP_N) {
                issues.add(new Issue("INVALID_LIMIT", "/query/limit", "1.." + MAX_TOP_N, "调整 limit"));
            }
        }
    }

    private SchemaFieldType validateTimeField(ReportSpec.Query query, Map<String, SchemaFieldType> schema, List<Issue> issues) {
        var field = query.timeField;
        if (ReportConstants.TIME_FIELD_RUN_STARTED_AT.equals(field) || ReportConstants.TIME_FIELD_CREATED_AT.equals(field)) {
            return SchemaFieldType.DATETIME;
        }
        var name = ReportSpecFields.dataField(field);
        var type = name != null ? schema.get(name) : null;
        if (type != SchemaFieldType.DATE && type != SchemaFieldType.DATETIME) {
            issues.add(new Issue("INVALID_TIME_FIELD", "/query/time_field",
                    "meta.run_started_at|meta.created_at|data.<DATE|DATETIME field>", "使用允许的时间字段"));
            return null;
        }
        query.timeField = ReportConstants.DATA_FIELD_PREFIX + name;
        return type;
    }

    private void validateFilters(ReportSpec.Query query, Map<String, SchemaFieldType> schema,
                                 Map<String, ReportSpec.Parameter> parameters, List<Issue> issues) {
        if (query.filters == null) return;
        if (query.filters.size() > MAX_FILTERS) issues.add(new Issue("TOO_MANY_FILTERS", "/query/filters", "<= " + MAX_FILTERS, "减少过滤条件"));
        for (int i = 0; i < query.filters.size(); i++) {
            var filter = query.filters.get(i);
            var path = "/query/filters/" + i;
            if (filter == null) {
                issues.add(new Issue("FILTER_FIELD_REQUIRED", path + "/field", "schema field", "补充字段"));
                continue;
            }
            var field = ReportSpecFields.recordField(filter.field);
            if (field == null || !schema.containsKey(field)) {
                issues.add(new Issue("UNKNOWN_FILTER_FIELD", path + "/field", "top-level schema field", "使用已声明的字段"));
                continue;
            }
            filter.field = field;
            var op = filter.op == null || filter.op.isBlank()
                    ? ReportConstants.FILTER_OP_EQ : filter.op.toUpperCase(Locale.ROOT);
            if (!FILTER_OPS.contains(op)) {
                issues.add(new Issue("INVALID_FILTER_OP", path + "/op", "EQ|NE|GT|GTE|LT|LTE|IN", "选择支持的过滤操作"));
                continue;
            }
            filter.op = op;
            if (filter.param != null && !filter.param.isBlank()) {
                STRUCTURE.validateFilterParameter(filter, schema.get(field), parameters, issues);
            } else {
                validateFilterValue(filter, schema.get(field), op, path, issues);
            }
        }
    }

    private void validateFilterValue(ReportSpec.Filter filter, SchemaFieldType type, String op, String path, List<Issue> issues) {
        if (ReportConstants.FILTER_OP_IN.equals(op)) {
            if (!(filter.value instanceof List<?> values) || values.isEmpty()) {
                issues.add(new Issue("INVALID_FILTER_VALUE", path + "/value", "non-empty array", "IN 需要非空数组"));
                return;
            }
            var normalized = new ArrayList<Object>(values.size());
            for (var value : values) {
                if (!ReportSpecFields.matchesType(type, value)) {
                    issues.add(new Issue("INVALID_FILTER_VALUE", path + "/value", ReportSpecFields.typeName(type), "值类型与字段不匹配"));
                    return;
                }
                normalized.add(ReportSpecFields.normalizeValue(type, value));
            }
            filter.value = normalized;
            return;
        }
        if (filter.value == null) {
            if (!ReportConstants.FILTER_OP_EQ.equals(op) && !ReportConstants.FILTER_OP_NE.equals(op)) {
                issues.add(new Issue("INVALID_FILTER_VALUE", path + "/value", "non-null scalar", "该操作需要非空值"));
            }
            return;
        }
        if (!ReportSpecFields.isScalar(filter.value)) {
            issues.add(new Issue("INVALID_FILTER_VALUE", path + "/value", "string|number|boolean|null", "只支持标量"));
            return;
        }
        if (type == SchemaFieldType.BOOLEAN && !ReportConstants.FILTER_OP_EQ.equals(op)
                && !ReportConstants.FILTER_OP_NE.equals(op)) {
            issues.add(new Issue("INVALID_FILTER_VALUE", path + "/value", "EQ|NE", "BOOLEAN 只支持等值比较"));
            return;
        }
        if (!ReportSpecFields.matchesType(type, filter.value)) {
            issues.add(new Issue("INVALID_FILTER_VALUE", path + "/value", ReportSpecFields.typeName(type), "值类型与字段不匹配"));
            return;
        }
        filter.value = ReportSpecFields.normalizeValue(type, filter.value);
    }

    private void validateRecordFields(ReportSpec.Query query, Map<String, SchemaFieldType> schema, List<Issue> issues) {
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
        var normalized = new ArrayList<String>(query.recordFields.size());
        for (var raw : query.recordFields) {
            var field = ReportSpecFields.recordField(raw);
            if (field == null || !schema.containsKey(field)) {
                issues.add(new Issue("UNKNOWN_RECORD_FIELD", "/query/record_fields", "top-level schema field", "使用已声明的字段: " + raw));
            } else {
                normalized.add(field);
            }
        }
        query.recordFields = normalized;
    }

    private void validateMeasures(ReportSpec.Query query, Map<String, SchemaFieldType> schema, List<Issue> issues) {
        var ids = new HashSet<String>();
        int measureCount = query.measures != null ? query.measures.size() : 0;
        int derivedCount = query.derivedMeasures != null ? query.derivedMeasures.size() : 0;
        if (query.measures == null || query.measures.isEmpty()) issues.add(new Issue("MEASURES_REQUIRED", "/query/measures", "1.." + MAX_MEASURES, "至少一个 measure"));
        if (measureCount + derivedCount > MAX_MEASURES) {
            issues.add(new Issue("TOO_MANY_MEASURES", "/query/measures", "total <= " + MAX_MEASURES, "减少 measure/派生"));
        }
        validateBaseMeasures(query, schema, ids, issues);
        ReportDerivedMeasureValidator.validate(query, ids, issues);
    }

    private void validateBaseMeasures(ReportSpec.Query query, Map<String, SchemaFieldType> schema,
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
            var op = measure.op == null ? "" : measure.op.toUpperCase(Locale.ROOT);
            if (!Set.of(ReportConstants.OP_SUM, ReportConstants.OP_AVG, ReportConstants.OP_MIN, ReportConstants.OP_MAX,
                    ReportConstants.OP_COUNT, ReportConstants.OP_COUNT_DISTINCT, ReportConstants.OP_LAST).contains(op)) {
                issues.add(new Issue("INVALID_MEASURE_OP", path + "/op", "SUM|AVG|MIN|MAX|COUNT|COUNT_DISTINCT|LAST", "选择支持的聚合"));
                continue;
            }
            if (ReportConstants.OP_COUNT.equals(op)) continue;
            var field = ReportSpecFields.dataField(measure.field);
            if (field == null) {
                issues.add(new Issue("INVALID_MEASURE_FIELD", path + "/field", "data.<schema field>", "使用 data.<field>"));
                continue;
            }
            var type = schema.get(field);
            if (type == null) {
                issues.add(new Issue("UNKNOWN_MEASURE_FIELD", path + "/field", "schema field", "使用已声明的字段"));
            } else if (type != SchemaFieldType.NUMBER
                    && !ReportConstants.OP_LAST.equals(op)
                    && !ReportConstants.OP_COUNT_DISTINCT.equals(op)) {
                issues.add(new Issue("MEASURE_REQUIRES_NUMBER", path + "/field", "NUMBER", op + " 需要数值字段"));
            }
        }
    }

    private Set<String> referencedFields(List<ReportSpec.Component> components) {
        var fields = new LinkedHashSet<String>();
        for (var component : components) {
            if (component == null || component.query == null) continue;
            var query = component.query;
            var timeField = ReportSpecFields.dataField(query.timeField);
            if (timeField != null) fields.add(timeField);
            if (query.categoryField != null) {
                var field = ReportSpecFields.recordField(query.categoryField);
                if (field != null) fields.add(field);
            }
            if (query.filters != null) {
                for (var filter : query.filters) {
                    var field = filter != null ? ReportSpecFields.recordField(filter.field) : null;
                    if (field != null) fields.add(field);
                }
            }
            if (query.recordFields != null) {
                for (var raw : query.recordFields) {
                    var field = ReportSpecFields.recordField(raw);
                    if (field != null) fields.add(field);
                }
            }
            if (query.measures != null) {
                for (var measure : query.measures) {
                    var field = ReportSpecFields.dataField(measure != null ? measure.field : null);
                    if (field != null) fields.add(field);
                }
            }
        }
        return fields;
    }

    private Set<String> baseMeasureIds(ReportSpec.Query query) {
        var ids = new HashSet<String>();
        if (query.measures != null) for (var measure : query.measures) if (measure != null && measure.id != null) ids.add(measure.id);
        return ids;
    }

    private Map<String, SchemaFieldType> schema(Dataset dataset) {
        var schema = new java.util.LinkedHashMap<String, SchemaFieldType>();
        if (dataset.schema != null) {
            for (SchemaField field : dataset.schema) {
                if (field != null && field.name != null) schema.put(field.name, field.type != null ? field.type : SchemaFieldType.STRING);
            }
        }
        return schema;
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
                    JsonUtil.toJson(Map.of("issues", issues)));
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


