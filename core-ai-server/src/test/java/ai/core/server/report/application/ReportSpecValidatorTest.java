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

    private Dataset dataset() {
        var dataset = new Dataset();
        dataset.id = "ds-1";
        dataset.name = "comments";
        dataset.type = DatasetType.GENERAL;
        dataset.schema = List.of(field("positive_count", SchemaFieldType.NUMBER),
                field("total", SchemaFieldType.NUMBER), field("channel", SchemaFieldType.STRING));
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
