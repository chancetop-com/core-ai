package ai.core.server.report.application;

import ai.core.server.domain.SchemaFieldType;
import ai.core.server.report.domain.ReportConstants;
import ai.core.server.report.domain.ReportSpec;
import ai.core.utils.JsonUtil;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates the report-level structure (parameters and components) and the query/display rules that are
 * independent of the dataset schema. Kept separate from {@link ReportSpecValidator} to keep both files small.
 *
 * @author core-ai
 */
final class ReportSpecStructureValidator {
    static final int MAX_COMPONENTS = 12;
    static final int MAX_TITLE_LENGTH = 120;
    private static final Set<String> PARAMETER_TYPES = Set.of("STRING", "NUMBER", "DATE_RANGE");

    static ReportSpecStructureValidator get() {
        return new ReportSpecStructureValidator();
    }

    private ReportSpecStructureValidator() {
    }

    Map<String, ReportSpec.Parameter> parameters(ReportSpec spec, List<ReportSpecValidator.Issue> issues) {
        var parameters = new LinkedHashMap<String, ReportSpec.Parameter>();
        for (var parameter : spec.parameters != null ? spec.parameters : List.<ReportSpec.Parameter>of()) {
            if (parameter == null || parameter.name == null || parameter.name.isBlank()) {
                issues.add(new ReportSpecValidator.Issue("PARAMETER_NAME_REQUIRED", "/parameters", "non-blank name", "补充参数名"));
                continue;
            }
            if (parameters.putIfAbsent(parameter.name, parameter) != null) {
                issues.add(new ReportSpecValidator.Issue("DUPLICATE_PARAMETER", "/parameters/" + parameter.name, "unique name", "参数名不能重复"));
                continue;
            }
            var type = parameter.type == null ? "" : parameter.type.toUpperCase(java.util.Locale.ROOT);
            if (!PARAMETER_TYPES.contains(type)) {
                issues.add(new ReportSpecValidator.Issue("INVALID_PARAMETER_TYPE", "/parameters/" + parameter.name + "/type",
                        "STRING|NUMBER|DATE_RANGE", "选择参数类型"));
                continue;
            }
            parameter.type = type;
            if (Boolean.TRUE.equals(parameter.multiple) && "DATE_RANGE".equals(type)) {
                issues.add(new ReportSpecValidator.Issue("INVALID_PARAMETER_TYPE", "/parameters/" + parameter.name + "/multiple",
                        "false for DATE_RANGE", "DATE_RANGE 不能多选"));
            }
            validateParameterDefault(parameter, issues);
        }
        return parameters;
    }

    private void validateParameterDefault(ReportSpec.Parameter parameter, List<ReportSpecValidator.Issue> issues) {
        var value = parameter.defaultValue;
        if (value == null) return;
        var path = "/parameters/" + parameter.name + "/default";
        if ("DATE_RANGE".equals(parameter.type)) {
            try {
                var range = JsonUtil.fromJson(ReportSpec.TimeRange.class, JsonUtil.toJson(value));
                if (range == null || range.param != null) {
                    issues.add(new ReportSpecValidator.Issue("INVALID_PARAMETER_DEFAULT", path, "literal date range", "使用 kind/days/from/to"));
                } else {
                    validateTimeRange(range, Map.of(), issues);
                }
            } catch (RuntimeException | Error e) {
                issues.add(new ReportSpecValidator.Issue("INVALID_PARAMETER_DEFAULT", path, "literal date range", "使用 kind/days/from/to"));
            }
            return;
        }
        if (Boolean.TRUE.equals(parameter.multiple)) {
            if (!(value instanceof List<?> values)) {
                issues.add(new ReportSpecValidator.Issue("INVALID_PARAMETER_DEFAULT", path, "array", "多选参数使用数组默认值"));
                return;
            }
            for (var item : values) {
                if (!scalarMatches(parameter.type, item)) {
                    issues.add(new ReportSpecValidator.Issue("INVALID_PARAMETER_DEFAULT", path, typeName(parameter.type), "默认值类型不匹配"));
                    return;
                }
            }
        } else if (!scalarMatches(parameter.type, value)) {
            issues.add(new ReportSpecValidator.Issue("INVALID_PARAMETER_DEFAULT", path, typeName(parameter.type), "默认值类型不匹配"));
        }
    }

    private boolean scalarMatches(String type, Object value) {
        return switch (type) {
            case "STRING" -> value instanceof String;
            case "NUMBER" -> value instanceof Number;
            default -> false;
        };
    }

    List<ReportSpec.Component> components(ReportSpec spec, List<ReportSpecValidator.Issue> issues) {
        if (spec.components == null || spec.components.isEmpty()) {
            if (spec.query == null && spec.display == null) return List.of();
            var component = new ReportSpec.Component();
            component.id = "main";
            component.query = spec.query;
            component.display = spec.display;
            return List.of(component);
        }
        if (spec.query != null || spec.display != null) {
            issues.add(new ReportSpecValidator.Issue("COMPONENTS_WITH_TOP_LEVEL_QUERY", "/components",
                    "components only", "使用 components 时不要再提交顶层 query/display"));
        }
        if (spec.components.size() > MAX_COMPONENTS) {
            issues.add(new ReportSpecValidator.Issue("TOO_MANY_COMPONENTS", "/components", "<= " + MAX_COMPONENTS, "减少 component"));
        }
        var ids = new HashSet<String>();
        var components = new ArrayList<ReportSpec.Component>(spec.components.size());
        for (int i = 0; i < spec.components.size(); i++) {
            var component = spec.components.get(i);
            var path = "/components/" + i;
            if (component == null) {
                issues.add(new ReportSpecValidator.Issue("COMPONENT_REQUIRED", path, "object", "补充 component"));
                continue;
            }
            if (component.id == null || component.id.isBlank()) {
                component.id = "component-" + (i + 1);
            }
            if (!ids.add(component.id)) {
                issues.add(new ReportSpecValidator.Issue("DUPLICATE_COMPONENT_ID", path + "/id", "unique", "component id 不能重复"));
            }
            if (component.title != null && component.title.length() > MAX_TITLE_LENGTH) {
                issues.add(new ReportSpecValidator.Issue("COMPONENT_TITLE_TOO_LONG", path + "/title", "<= " + MAX_TITLE_LENGTH, "缩短标题"));
            }
            components.add(component);
        }
        return components;
    }

    void validateTimeRange(ReportSpec.TimeRange range, Map<String, ReportSpec.Parameter> parameters,
                           List<ReportSpecValidator.Issue> issues) {
        if (range.param != null && !range.param.isBlank()) {
            var parameter = parameters.get(range.param);
            if (parameter == null || !"DATE_RANGE".equals(parameter.type)) {
                issues.add(new ReportSpecValidator.Issue("UNKNOWN_PARAMETER", "/query/time_range/param",
                        "DATE_RANGE parameter", "引用已声明的 DATE_RANGE 参数"));
            }
            return;
        }
        if (!Set.of(ReportConstants.RANGE_TODAY, ReportConstants.RANGE_YESTERDAY, ReportConstants.RANGE_LAST_N_COMPLETE_DAYS,
                ReportConstants.RANGE_ROLLING_DAYS, ReportConstants.RANGE_FIXED).contains(range.kind)) {
            issues.add(new ReportSpecValidator.Issue("INVALID_TIME_RANGE", "/query/time_range/kind",
                    "TODAY|YESTERDAY|LAST_N_COMPLETE_DAYS|ROLLING_DAYS|FIXED", "选择时间范围"));
            return;
        }
        if ((ReportConstants.RANGE_LAST_N_COMPLETE_DAYS.equals(range.kind) || ReportConstants.RANGE_ROLLING_DAYS.equals(range.kind))
                && (range.days == null || range.days < 1 || range.days > ReportSpecValidator.MAX_QUERY_DAYS)) {
            issues.add(new ReportSpecValidator.Issue("INVALID_TIME_RANGE_DAYS", "/query/time_range/days",
                    "1.." + ReportSpecValidator.MAX_QUERY_DAYS, "调整天数"));
        }
        if (ReportConstants.RANGE_FIXED.equals(range.kind)) {
            if (range.from == null || range.to == null) {
                issues.add(new ReportSpecValidator.Issue("FIXED_RANGE_REQUIRED", "/query/time_range", "from/to", "固定窗口需要 from/to"));
            } else {
                try {
                    java.time.Instant.parse(range.from);
                    java.time.Instant.parse(range.to);
                } catch (java.time.format.DateTimeParseException e) {
                    issues.add(new ReportSpecValidator.Issue("INVALID_FIXED_RANGE", "/query/time_range", "ISO instant", "使用 UTC instant"));
                }
            }
        }
    }

    void validateTimezone(String timezone, List<ReportSpecValidator.Issue> issues) {
        if (timezone == null || timezone.isBlank()) {
            issues.add(new ReportSpecValidator.Issue("TIMEZONE_REQUIRED", "/query/timezone", "IANA zone", "指定报表时区"));
            return;
        }
        try {
            ZoneId.of(timezone);
        } catch (RuntimeException e) {
            issues.add(new ReportSpecValidator.Issue("INVALID_TIMEZONE", "/query/timezone", "IANA zone", "检查时区"));
        }
    }

    void validateFilterParameter(ReportSpec.Filter filter, SchemaFieldType fieldType,
                                 Map<String, ReportSpec.Parameter> parameters, List<ReportSpecValidator.Issue> issues) {
        var parameter = parameters.get(filter.param);
        if (parameter == null) {
            issues.add(new ReportSpecValidator.Issue("UNKNOWN_PARAMETER", "/query/filters/param",
                    "declared parameter", "引用已声明的参数"));
            return;
        }
        if (filter.value != null) {
            issues.add(new ReportSpecValidator.Issue("FILTER_VALUE_AND_PARAM", "/query/filters/value",
                    "value xor param", "value 和 param 只能二选一"));
        }
        var op = filter.op == null ? ReportConstants.FILTER_OP_EQ : filter.op;
        var expectsList = ReportConstants.FILTER_OP_IN.equals(op);
        if (expectsList != Boolean.TRUE.equals(parameter.multiple)) {
            issues.add(new ReportSpecValidator.Issue("PARAMETER_MULTIPLICITY_MISMATCH", "/query/filters/param",
                    expectsList ? "multiple parameter" : "scalar parameter", "参数多选设置与操作符不匹配"));
        }
        var parameterType = parameter.type;
        var compatible = switch (fieldType != null ? fieldType : SchemaFieldType.STRING) {
            case NUMBER -> "NUMBER".equals(parameterType);
            case STRING, DATE, DATETIME -> "STRING".equals(parameterType);
            case BOOLEAN -> false;
        };
        if (!compatible) {
            issues.add(new ReportSpecValidator.Issue("PARAMETER_TYPE_MISMATCH", "/query/filters/param",
                    fieldType != null ? fieldType.name() : SchemaFieldType.STRING.name(), "参数类型与字段不匹配"));
        }
    }

    void validateDisplay(ReportSpec.Query query, ReportSpec.Display display, List<ReportSpecValidator.Issue> issues) {
        var type = display.type;
        if (!Set.of(ReportConstants.DISPLAY_METRIC, ReportConstants.DISPLAY_LINE, ReportConstants.DISPLAY_BAR,
                ReportConstants.DISPLAY_TABLE).contains(type)) {
            issues.add(new ReportSpecValidator.Issue("INVALID_DISPLAY_TYPE", "/display/type", "METRIC|LINE|BAR|TABLE", "选择展示类型"));
            return;
        }
        boolean hasBucket = query.bucket != null && !ReportConstants.BUCKET_NONE.equals(query.bucket);
        boolean hasCategory = query.categoryField != null && !query.categoryField.isBlank();
        if (ReportConstants.DISPLAY_METRIC.equals(type)) {
            if (hasBucket || hasCategory) {
                issues.add(new ReportSpecValidator.Issue("METRIC_SINGLE_VALUE", "/display/type", "bucket/category NONE", "METRIC 只能单值"));
            }
            int measures = (query.measures != null ? query.measures.size() : 0) + (query.derivedMeasures != null ? query.derivedMeasures.size() : 0);
            if (measures != 1) {
                issues.add(new ReportSpecValidator.Issue("METRIC_ONE_MEASURE", "/display/series", "exactly one measure", "METRIC 只展示一个指标"));
            }
        } else if (ReportConstants.DISPLAY_LINE.equals(type) || ReportConstants.DISPLAY_BAR.equals(type)) {
            if (!hasBucket && !hasCategory) {
                issues.add(new ReportSpecValidator.Issue("CHART_AXIS_REQUIRED", "/display/type", "bucket or category", "图表需要 x 轴"));
            }
            if (hasBucket && hasCategory) {
                issues.add(new ReportSpecValidator.Issue("CHART_AXIS_AMBIGUOUS", "/display/type", "bucket XOR category", "首版不同时使用 bucket 和 category"));
            }
        } else if (ReportConstants.DISPLAY_TABLE.equals(type) && ReportConstants.MODE_RECORDS.equals(query.mode)) {
            return;
        }
        if (display.series != null) validateSeries(query, display, issues);
        if (display.precision != null && (display.precision < 0 || display.precision > 6)) {
            issues.add(new ReportSpecValidator.Issue("INVALID_PRECISION", "/display/precision", "0..6", "调整精度"));
        }
    }

    private void validateSeries(ReportSpec.Query query, ReportSpec.Display display, List<ReportSpecValidator.Issue> issues) {
        var ids = new HashSet<String>();
        addMeasureIds(ids, query.measures);
        addDerivedIds(ids, query.derivedMeasures);
        if (display.series.size() > ReportSpecValidator.MAX_SERIES) {
            issues.add(new ReportSpecValidator.Issue("TOO_MANY_SERIES", "/display/series", "<= " + ReportSpecValidator.MAX_SERIES, "减少 series"));
        }
        for (int i = 0; i < display.series.size(); i++) {
            var series = display.series.get(i);
            if (series == null || series.field == null || !ids.contains(series.field)) {
                issues.add(new ReportSpecValidator.Issue("UNKNOWN_SERIES_FIELD", "/display/series/" + i + "/field",
                        "measure id", "引用已声明的 measure"));
            }
            if (series != null && series.format != null && !Set.of(ReportConstants.FORMAT_NUMBER, ReportConstants.FORMAT_PERCENT,
                    ReportConstants.FORMAT_CURRENCY).contains(series.format)) {
                issues.add(new ReportSpecValidator.Issue("INVALID_FORMAT", "/display/series/" + i + "/format",
                        "NUMBER|PERCENT|CURRENCY", "选择格式"));
            }
        }
    }

    private void addMeasureIds(Set<String> ids, List<ReportSpec.Measure> measures) {
        if (measures == null) return;
        for (var measure : measures) if (measure != null && measure.id != null) ids.add(measure.id);
    }

    private void addDerivedIds(Set<String> ids, List<ReportSpec.DerivedMeasure> measures) {
        if (measures == null) return;
        for (var measure : measures) if (measure != null && measure.id != null) ids.add(measure.id);
    }

    private String typeName(String type) {
        return type != null ? type : "STRING";
    }
}
