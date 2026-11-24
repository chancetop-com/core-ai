package ai.core.server.report.application;

import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetType;
import ai.core.server.domain.SchemaField;
import ai.core.server.domain.SchemaFieldType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportSpecValidatorTest {
    private final ReportSpecValidator validator = new ReportSpecValidator();

    @Test
    void validatesAggregateAndNormalizesSnakeCase() {
        var dataset = dataset();
        var result = validator.validate("Comments", "daily", spec("""
                {
                  "spec_version": 1,
                  "source": {"dataset_id": "ds-1"},
                  "query": {
                    "mode": "AGGREGATE",
                    "time_field": "meta.run_started_at",
                    "time_range": {"kind": "LAST_N_COMPLETE_DAYS", "days": 30},
                    "timezone": "Asia/Shanghai",
                    "bucket": "DAY",
                    "measures": [
                      {"id": "positive", "label": "positive", "field": "data.positive_count", "op": "SUM"},
                      {"id": "total", "label": "total", "field": "data.total", "op": "SUM"}
                    ],
                    "derived_measures": [
                      {"id": "rate", "label": "rate", "op": "RATIO", "numerator": "positive", "denominator": "total"}
                    ]
                  },
                  "display": {
                    "type": "LINE",
                    "x": "bucket",
                    "series": [
                      {"field": "total", "axis": "LEFT", "format": "NUMBER"},
                      {"field": "rate", "axis": "RIGHT", "format": "PERCENT"}
                    ]
                  }
                }
                """), dataset);

        assertTrue(result.specJson().contains("\"spec_version\":1"));
        assertTrue(result.specJson().contains("\"dataset_id\":\"ds-1\""));
        assertEquals(2, result.dependency().fields.size());
        assertEquals("positive_count", result.dependency().fields.getFirst().name);
    }

    @Test
    void rejectsUnknownMeasureField() {
        var error = assertThrows(ReportSpecValidator.ReportValidationException.class,
                () -> validator.validate("bad", null, spec("""
                        {
                          "spec_version": 1,
                          "source": {"dataset_id": "ds-1"},
                          "query": {
                            "mode": "AGGREGATE",
                            "time_field": "meta.run_started_at",
                            "time_range": {"kind": "LAST_N_COMPLETE_DAYS", "days": 7},
                            "timezone": "UTC",
                            "bucket": "DAY",
                            "measures": [{"id": "x", "field": "data.unknown", "op": "SUM"}]
                          },
                          "display": {"type": "LINE", "x": "bucket", "series": [{"field": "x"}]}
                        }
                        """), dataset()));
        assertTrue(error.issues().stream().anyMatch(issue -> "UNKNOWN_MEASURE_FIELD".equals(issue.code())));
    }

    @Test
    void rejectsBucketAndCategoryTogether() {
        var error = assertThrows(ReportSpecValidator.ReportValidationException.class,
                () -> validator.validate("bad", null, spec("""
                        {
                          "spec_version": 1,
                          "source": {"dataset_id": "ds-1"},
                          "query": {
                            "mode": "AGGREGATE",
                            "time_field": "meta.run_started_at",
                            "time_range": {"kind": "LAST_N_COMPLETE_DAYS", "days": 7},
                            "timezone": "UTC",
                            "bucket": "DAY",
                            "category_field": "channel",
                            "measures": [{"id": "x", "field": "data.total", "op": "SUM"}]
                          },
                          "display": {"type": "LINE", "x": "bucket", "series": [{"field": "x"}]}
                        }
                        """), dataset()));
        assertTrue(error.issues().stream().anyMatch(issue -> "CHART_AXIS_AMBIGUOUS".equals(issue.code())));
    }

    @Test
    void acceptsBusinessDateAsTimeAxisAndTypedFilters() {
        var result = validator.validate("daily", null, spec("""
                {
                  "spec_version": 1,
                  "source": {"dataset_id": "ds-1"},
                  "query": {
                    "mode": "AGGREGATE",
                    "time_field": "data.business_date",
                    "time_range": {"kind": "LAST_N_COMPLETE_DAYS", "days": 30},
                    "timezone": "Asia/Shanghai",
                    "bucket": "DAY",
                    "category_field": "data.channel",
                    "filters": [
                      {"field": "data.channel", "op": "IN", "value": ["web", "app"]},
                      {"field": "business_date", "op": "GTE", "value": "2026-10-22"}
                    ],
                    "measures": [{"id": "total", "field": "data.total", "op": "SUM"}]
                  },
                  "display": {"type": "TABLE", "precision": 0}
                }
                """), dataset());

        assertEquals("data.business_date", result.spec().query.timeField);
        assertEquals("channel", result.spec().query.categoryField);
        assertEquals("channel", result.spec().query.filters.get(0).field);
        assertEquals("2026-10-22", result.spec().query.filters.get(1).value);
        assertTrue(result.dependency().fields.stream().anyMatch(field -> "business_date".equals(field.name)));
    }

    @Test
    void rejectsStringBusinessDateAsTimeAxis() {
        var error = assertThrows(ReportSpecValidator.ReportValidationException.class,
                () -> validator.validate("bad", null, spec("""
                        {
                          "spec_version": 1,
                          "source": {"dataset_id": "ds-1"},
                          "query": {
                            "mode": "AGGREGATE",
                            "time_field": "data.channel",
                            "time_range": {"kind": "LAST_N_COMPLETE_DAYS", "days": 7},
                            "timezone": "UTC",
                            "bucket": "DAY",
                            "measures": [{"id": "x", "field": "data.total", "op": "SUM"}]
                          },
                          "display": {"type": "LINE", "x": "bucket", "series": [{"field": "x"}]}
                        }
                        """), dataset()));
        assertTrue(error.issues().stream().anyMatch(issue -> "INVALID_TIME_FIELD".equals(issue.code())));
    }

    @Test
    void rejectsHourBucketForDateAxis() {
        var error = assertThrows(ReportSpecValidator.ReportValidationException.class,
                () -> validator.validate("bad", null, spec("""
                        {
                          "spec_version": 1,
                          "source": {"dataset_id": "ds-1"},
                          "query": {
                            "mode": "AGGREGATE",
                            "time_field": "data.business_date",
                            "time_range": {"kind": "LAST_N_COMPLETE_DAYS", "days": 7},
                            "timezone": "UTC",
                            "bucket": "HOUR",
                            "measures": [{"id": "x", "field": "data.total", "op": "SUM"}]
                          },
                          "display": {"type": "LINE", "x": "bucket", "series": [{"field": "x"}]}
                        }
                        """), dataset()));
        assertTrue(error.issues().stream().anyMatch(issue -> "INVALID_BUCKET".equals(issue.code())));
    }

    @Test
    void rejectsUnsupportedFilterOp() {
        var error = assertThrows(ReportSpecValidator.ReportValidationException.class,
                () -> validator.validate("bad", null, spec("""
                        {
                          "spec_version": 1,
                          "source": {"dataset_id": "ds-1"},
                          "query": {
                            "mode": "AGGREGATE",
                            "time_field": "meta.run_started_at",
                            "time_range": {"kind": "LAST_N_COMPLETE_DAYS", "days": 7},
                            "timezone": "UTC",
                            "bucket": "DAY",
                            "filters": [{"field": "channel", "op": "LIKE", "value": "web"}],
                            "measures": [{"id": "x", "field": "data.total", "op": "SUM"}]
                          },
                          "display": {"type": "LINE", "x": "bucket", "series": [{"field": "x"}]}
                        }
                        """), dataset()));
        assertTrue(error.issues().stream().anyMatch(issue -> "INVALID_FILTER_OP".equals(issue.code())));
    }

    @Test
    void rejectsFilterValueTypeMismatch() {
        var error = assertThrows(ReportSpecValidator.ReportValidationException.class,
                () -> validator.validate("bad", null, spec("""
                        {
                          "spec_version": 1,
                          "source": {"dataset_id": "ds-1"},
                          "query": {
                            "mode": "AGGREGATE",
                            "time_field": "meta.run_started_at",
                            "time_range": {"kind": "LAST_N_COMPLETE_DAYS", "days": 7},
                            "timezone": "UTC",
                            "bucket": "DAY",
                            "filters": [{"field": "total", "op": "GTE", "value": "10"}],
                            "measures": [{"id": "x", "field": "data.total", "op": "SUM"}]
                          },
                          "display": {"type": "LINE", "x": "bucket", "series": [{"field": "x"}]}
                        }
                        """), dataset()));
        assertTrue(error.issues().stream().anyMatch(issue -> "INVALID_FILTER_VALUE".equals(issue.code())));
    }

    @Test
    void acceptsParameterizedMultiComponentSpec() {
        var result = validator.validate("dashboard", null, spec("""
                {
                  "spec_version": 1,
                  "source": {"dataset_id": "ds-1"},
                  "parameters": [
                    {"name": "stores", "type": "STRING", "multiple": true, "default": ["web"]},
                    {"name": "range", "type": "DATE_RANGE", "default": {"kind": "LAST_N_COMPLETE_DAYS", "days": 30}}
                  ],
                  "components": [
                    {"id": "kpi", "title": "KPI",
                     "query": {"mode": "AGGREGATE", "time_field": "data.business_date", "time_range": {"param": "range"},
                               "timezone": "Asia/Shanghai", "bucket": "NONE",
                               "filters": [{"field": "channel", "op": "IN", "param": "stores"}],
                               "measures": [{"id": "total", "field": "data.total", "op": "SUM"}]},
                     "display": {"type": "METRIC"}},
                    {"id": "trend",
                     "query": {"mode": "AGGREGATE", "time_field": "data.business_date", "time_range": {"param": "range"},
                               "timezone": "Asia/Shanghai", "bucket": "DAY",
                               "measures": [{"id": "total", "field": "data.total", "op": "SUM"}]},
                     "display": {"type": "LINE", "x": "bucket", "series": [{"field": "total"}]}}
                  ]
                }
                """), dataset());

        assertEquals("data.business_date", result.spec().components.get(0).query.timeField);
        assertTrue(result.dependency().fields.stream().anyMatch(field -> "business_date".equals(field.name)));
        assertTrue(result.dependency().fields.stream().anyMatch(field -> "channel".equals(field.name)));
    }

    @Test
    void rejectsUnknownParameterReference() {
        var error = assertThrows(ReportSpecValidator.ReportValidationException.class,
                () -> validator.validate("bad", null, spec("""
                        {
                          "spec_version": 1,
                          "source": {"dataset_id": "ds-1"},
                          "parameters": [{"name": "stores", "type": "STRING", "multiple": true, "default": ["web"]}],
                          "components": [{"id": "kpi",
                            "query": {"mode": "AGGREGATE", "time_field": "data.business_date",
                                      "time_range": {"param": "missing"}, "timezone": "UTC", "bucket": "NONE",
                                      "measures": [{"id": "total", "field": "data.total", "op": "SUM"}]},
                            "display": {"type": "METRIC"}}]
                        }
                        """), dataset()));
        assertTrue(error.issues().stream().anyMatch(issue -> "UNKNOWN_PARAMETER".equals(issue.code())));
    }

    @Test
    void rejectsMultipleParameterForEqualsFilter() {
        var error = assertThrows(ReportSpecValidator.ReportValidationException.class,
                () -> validator.validate("bad", null, spec("""
                        {
                          "spec_version": 1,
                          "source": {"dataset_id": "ds-1"},
                          "parameters": [{"name": "stores", "type": "STRING", "multiple": true, "default": ["web"]}],
                          "components": [{"id": "kpi",
                            "query": {"mode": "AGGREGATE", "time_field": "meta.run_started_at",
                                      "time_range": {"kind": "LAST_N_COMPLETE_DAYS", "days": 7}, "timezone": "UTC",
                                      "bucket": "NONE",
                                      "filters": [{"field": "channel", "op": "EQ", "param": "stores"}],
                                      "measures": [{"id": "total", "field": "data.total", "op": "SUM"}]},
                            "display": {"type": "METRIC"}}]
                        }
                        """), dataset()));
        assertTrue(error.issues().stream().anyMatch(issue -> "PARAMETER_MULTIPLICITY_MISMATCH".equals(issue.code())));
    }

    private Dataset dataset() {
        var dataset = new Dataset();
        dataset.id = "ds-1";
        dataset.name = "comments";
        dataset.type = DatasetType.GENERAL;
        dataset.schema = List.of(field("positive_count", SchemaFieldType.NUMBER),
                field("total", SchemaFieldType.NUMBER), field("channel", SchemaFieldType.STRING),
                field("business_date", SchemaFieldType.DATE));
        return dataset;
    }

    private SchemaField field(String name, SchemaFieldType type) {
        var field = new SchemaField();
        field.name = name;
        field.type = type;
        return field;
    }

    private String spec(String json) {
        return json;
    }
}
