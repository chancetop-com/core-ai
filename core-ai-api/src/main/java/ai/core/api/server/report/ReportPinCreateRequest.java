package ai.core.api.server.report;

import core.framework.api.json.Property;

/**
 * @author core-ai
 */
public class ReportPinCreateRequest {
    @Property(name = "report_id")
    public String reportId;

    @Property(name = "width")
    public String width;

    @Property(name = "expected_layout_revision")
    public Long expectedLayoutRevision;
}
