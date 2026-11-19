package ai.core.api.server.report;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class ListReportsResponse {
    @Property(name = "reports")
    public List<ReportView> reports;

    @Property(name = "total")
    public Long total;
}
