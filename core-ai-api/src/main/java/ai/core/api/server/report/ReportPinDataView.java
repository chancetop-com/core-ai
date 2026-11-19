package ai.core.api.server.report;

import core.framework.api.json.Property;

/**
 * @author core-ai
 */
public class ReportPinDataView {
    @Property(name = "pin_id")
    public String pinId;

    @Property(name = "report_id")
    public String reportId;

    @Property(name = "data")
    public ReportDataView data;

    @Property(name = "error_code")
    public String errorCode;

    @Property(name = "error_message")
    public String errorMessage;
}
