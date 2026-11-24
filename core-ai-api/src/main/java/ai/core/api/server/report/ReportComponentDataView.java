package ai.core.api.server.report;

import core.framework.api.json.Property;

/**
 * One component of a rendered report. A legacy single-chart report has exactly one component.
 *
 * @author core-ai
 */
public class ReportComponentDataView {
    @Property(name = "id")
    public String id;

    @Property(name = "title")
    public String title;

    @Property(name = "display_type")
    public String displayType;

    @Property(name = "status")
    public String status;

    @Property(name = "from_inclusive")
    public String fromInclusive;

    @Property(name = "to_exclusive")
    public String toExclusive;

    @Property(name = "timezone")
    public String timezone;

    @Property(name = "columns_json")
    public String columnsJson;

    @Property(name = "rows_json")
    public String rowsJson;

    @Property(name = "matched_record_count")
    public Long matchedRecordCount;

    @Property(name = "invalid_value_count")
    public Long invalidValueCount;

    @Property(name = "source_time")
    public String sourceTime;

    @Property(name = "source_updated_at")
    public String sourceUpdatedAt;

    @Property(name = "evaluated_at")
    public String evaluatedAt;

    @Property(name = "freshness_status")
    public String freshnessStatus;

    @Property(name = "has_more")
    public Boolean hasMore;
}
