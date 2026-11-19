package ai.core.api.server.report;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class ReportPinsDataResponse {
    @Property(name = "items")
    public List<ReportPinDataView> items;
}
