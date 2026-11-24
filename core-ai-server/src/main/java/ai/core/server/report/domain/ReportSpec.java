package ai.core.server.report.domain;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Normalized report definition. The JSON wire format is snake_case, matching spec_version=1.
 * Explicit JsonProperty annotations are intentional: the shared JsonUtil introspector does not
 * apply snake-case naming to these public-field beans.
 *
 * @author core-ai
 */
public class ReportSpec {
    @Property(name = "spec_version")
    public Integer specVersion;
    @Property(name = "source")
    public Source source;
    @Property(name = "query")
    public Query query;
    @Property(name = "display")
    public Display display;
    @Property(name = "freshness")
    public Freshness freshness;

    public static class Source {
        @Property(name = "dataset_id")
        public String datasetId;
    }

    public static class Query {
        @Property(name = "mode")
        public String mode;
        @Property(name = "time_field")
        public String timeField;
        @Property(name = "time_range")
        public TimeRange timeRange;
        @Property(name = "timezone")
        public String timezone;
        @Property(name = "bucket")
        public String bucket;
        @Property(name = "category_field")
        public String categoryField;
        @Property(name = "filters")
        public List<Filter> filters;
        @Property(name = "measures")
        public List<Measure> measures;
        @Property(name = "derived_measures")
        public List<DerivedMeasure> derivedMeasures;
        @Property(name = "record_fields")
        public List<String> recordFields;
    }

    public static class TimeRange {
        @Property(name = "kind")
        public String kind;
        @Property(name = "days")
        public Integer days;
        @Property(name = "from")
        public String from;
        @Property(name = "to")
        public String to;
    }

    public static class Filter {
        @Property(name = "field")
        public String field;
        @Property(name = "op")
        public String op;
        @Property(name = "value")
        public Object value;
    }

    public static class Measure {
        @Property(name = "id")
        public String id;
        @Property(name = "label")
        public String label;
        @Property(name = "field")
        public String field;
        @Property(name = "op")
        public String op;
    }

    public static class DerivedMeasure {
        @Property(name = "id")
        public String id;
        @Property(name = "label")
        public String label;
        @Property(name = "op")
        public String op;
        @Property(name = "numerator")
        public String numerator;
        @Property(name = "denominator")
        public String denominator;
    }

    public static class Display {
        @Property(name = "type")
        public String type;
        @Property(name = "x")
        public String x;
        @Property(name = "series")
        public List<Series> series;
        @Property(name = "columns")
        public List<String> columns;
        @Property(name = "precision")
        public Integer precision;
    }

    public static class Series {
        @Property(name = "field")
        public String field;
        @Property(name = "axis")
        public String axis;
        @Property(name = "format")
        public String format;
    }

    public static class Freshness {
        @Property(name = "expected_interval_hours")
        public Integer expectedIntervalHours;
    }
}
