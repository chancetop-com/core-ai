package ai.core.server.report.application;

import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetType;
import ai.core.server.domain.SchemaField;
import ai.core.server.domain.SchemaFieldType;
import ai.core.server.report.domain.ReportConstants;
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

    @Test
    void acceptsCompositeMeasuresAndRatioKpi() {
        var result = validator.validate("kpi", null, spec("""
                {
                  "spec_version": 1,
                  "source": {"dataset_id": "ds-1"},
                  "parameters": [
                    {"name": "stores", "type": "STRING", "multiple": true, "default": ["web"], "options_field": "channel"},
                    {"name": "range", "type": "DATE_RANGE", "default": {"kind": "LAST_N_DAYS_OF_DATA", "days": 30}}
                  ],
                  "components": [{
                    "id": "kpi",
                    "query": {
                      "mode": "AGGREGATE", "time_field": "data.business_date", "time_range": {"param": "range"},
                      "timezone": "Asia/Shanghai", "bucket": "NONE",
                      "filters": [{"field": "channel", "op": "IN", "param": "stores"}],
                      "measures": [
                        {"id": "positive", "field": "data.positive_count", "op": "SUM"},
                        {"id": "total", "field": "data.total", "op": "SUM"}
                      ],
                      "derived_measures": [
                        {"id": "summed", "op": "ADD", "inputs": ["positive", "total"]},
                        {"id": "share", "op": "RATIO", "numerator": "summed", "denominator": "total"}
                      ]
                    },
                    "display": {"type": "METRIC", "series": [{"field": "share", "format": "PERCENT"}]}
                  }]
                }
                """), dataset());

        assertEquals("channel", result.spec().parameters.get(0).optionsField);
        assertEquals(ReportConstants.OP_ADD, result.spec().components.get(0).query.derivedMeasures.get(0).op);
        assertTrue(result.dependency().fields.stream().anyMatch(field -> "channel".equals(field.name)));
    }

    @Test
    void acceptsRankingCompositionAndMovingAverage() {
        var result = validator.validate("dashboard", null, spec("""
                {
                  "spec_version": 1,
                  "source": {"dataset_id": "ds-1"},
                  "components": [
                    {"id": "ranking",
                     "query": {"mode": "AGGREGATE", "time_field": "data.business_date",
                       "time_range": {"kind": "SOURCE_RANGE"}, "timezone": "Asia/Shanghai", "bucket": "NONE",
                       "category_field": "channel",
                       "measures": [{"id": "total", "field": "data.total", "op": "SUM"}],
                       "order_by": {"measure": "total", "direction": "DESC"}, "limit": 5},
                     "display": {"type": "BAR", "orientation": "HORIZONTAL", "series": [{"field": "total"}]}},
                    {"id": "composition",
                     "query": {"mode": "AGGREGATE", "time_field": "data.business_date",
                       "time_range": {"kind": "LAST_N_DAYS_OF_DATA", "days": 30}, "timezone": "Asia/Shanghai", "bucket": "NONE",
                       "measures": [
                         {"id": "positive", "field": "data.positive_count", "op": "SUM"},
                         {"id": "total", "field": "data.total", "op": "SUM"}
                       ]},
                     "display": {"type": "MEASURE_BAR", "series": [{"field": "positive"}, {"field": "total"}]}},
                    {"id": "trend",
                     "query": {"mode": "AGGREGATE", "time_field": "data.business_date",
                       "time_range": {"kind": "LAST_N_DAYS_OF_DATA", "days": 30}, "timezone": "Asia/Shanghai", "bucket": "DAY",
                       "measures": [{"id": "total", "field": "data.total", "op": "SUM"}],
                       "derived_measures": [{"id": "total_ma7", "op": "MOVING_AVG", "input": "total", "window": 7}]},
                     "display": {"type": "LINE", "series": [{"field": "total"}, {"field": "total_ma7"}]}}
                  ]
                }
                """), dataset());

        assertEquals("DESC", result.spec().components.get(0).query.orderBy.direction);
        assertEquals(7, result.spec().components.get(2).query.derivedMeasures.get(0).window);
    }

    @Test
    void rejectsSelfReferencingDerivedMeasure() {
        var error = assertThrows(ReportSpecValidator.ReportValidationException.class,
                () -> validator.validate("bad", null, spec("""
                        {
                          "spec_version": 1,
                          "source": {"dataset_id": "ds-1"},
                          "query": {
                            "mode": "AGGREGATE", "time_field": "data.business_date",
                            "time_range": {"kind": "LAST_N_DAYS_OF_DATA", "days": 7}, "timezone": "UTC", "bucket": "DAY",
                            "measures": [{"id": "total", "field": "data.total", "op": "SUM"}],
                            "derived_measures": [{"id": "double", "op": "ADD", "inputs": ["double", "total"]}]
                          },
                          "display": {"type": "LINE", "series": [{"field": "total"}, {"field": "double"}]}
                        }
                        """), dataset()));
        assertTrue(error.issues().stream().anyMatch(issue -> "ADD_REFERENCES_UNKNOWN".equals(issue.code())));
    }

    @Test
    void rejectsMetricWithTwoOutputMeasures() {
        var error = assertThrows(ReportSpecValidator.ReportValidationException.class,
                () -> validator.validate("bad", null, spec("""
                        {
                          "spec_version": 1,
                          "source": {"dataset_id": "ds-1"},
                          "query": {
                            "mode": "AGGREGATE", "time_field": "data.business_date",
                            "time_range": {"kind": "LAST_N_DAYS_OF_DATA", "days": 7}, "timezone": "UTC", "bucket": "NONE",
                            "measures": [
                              {"id": "positive", "field": "data.positive_count", "op": "SUM"},
                              {"id": "total", "field": "data.total", "op": "SUM"}
                            ]
                          },
                          "display": {"type": "METRIC"}
                        }
                        """), dataset()));
        assertTrue(error.issues().stream().anyMatch(issue -> "METRIC_ONE_MEASURE".equals(issue.code())));
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
