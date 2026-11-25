package ai.core.server.report.domain;

import core.framework.api.json.Property;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

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
    @Property(name = "parameters")
    public List<Parameter> parameters;
    @Property(name = "components")
    public List<Component> components;
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

    public static class Parameter {
        @Property(name = "name")
        public String name;
        @Property(name = "type")
        public String type;
        @Property(name = "multiple")
        public Boolean multiple;
        @Property(name = "default")
        public Object defaultValue;
        @Property(name = "options_field")
        public String optionsField;
    }

    public static class Component {
        @Property(name = "id")
        public String id;
        @Property(name = "title")
        public String title;
        @Property(name = "query")
        public Query query;
        @Property(name = "display")
        public Display display;
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
        @Property(name = "order_by")
        public OrderBy orderBy;
        @Property(name = "limit")
        public Integer limit;
    }

    public static class OrderBy {
        @Property(name = "measure")
        public String measure;
        @Property(name = "direction")
        public String direction;
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
        @Property(name = "param")
        public String param;
    }

    public static class Filter {
        @Property(name = "field")
        public String field;
        @Property(name = "op")
        public String op;
        @Property(name = "value")
        public Object value;
        @SuppressFBWarnings("UWF_NULL_FIELD")
        @Property(name = "param")
        public String param;
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
        @Property(name = "inputs")
        public List<String> inputs;
        @Property(name = "input")
        public String input;
        @Property(name = "window")
        public Integer window;
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
        @Property(name = "orientation")
        public String orientation;
        @Property(name = "width")
        public String width;
    }

    public static class Series {
        @Property(name = "field")
        public String field;
        @Property(name = "axis")
        public String axis;
        @Property(name = "format")
        public String format;
        @Property(name = "label")
        public String label;
    }

    public static class Freshness {
        @Property(name = "expected_interval_hours")
        public Integer expectedIntervalHours;
    }
}
