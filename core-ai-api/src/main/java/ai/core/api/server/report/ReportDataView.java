package ai.core.api.server.report;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class ReportDataView {
    @Property(name = "report_id")
    public String reportId;

    @Property(name = "report_revision")
    public Long reportRevision;

    @Property(name = "spec_version")
    public Integer specVersion;

    @Property(name = "components")
    public List<ReportComponentDataView> components;

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
