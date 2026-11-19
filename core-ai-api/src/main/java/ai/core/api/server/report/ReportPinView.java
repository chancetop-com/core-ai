package ai.core.api.server.report;

import core.framework.api.json.Property;

/**
 * @author core-ai
 */
public class ReportPinView {
    @Property(name = "id")
    public String id;

    @Property(name = "report_id")
    public String reportId;

    @Property(name = "position")
    public Integer position;

    @Property(name = "width")
    public String width;

    @Property(name = "title")
    public String title;

    @Property(name = "display_type")
    public String displayType;

    @Property(name = "dataset_id")
    public String datasetId;

    @Property(name = "report_revision")
    public Long reportRevision;

    @Property(name = "updated_at")
    public String updatedAt;
}
