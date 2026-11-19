package ai.core.api.server.report;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class ReportLayoutRequest {
    @Property(name = "expected_layout_revision")
    public Long expectedLayoutRevision;

    @Property(name = "items")
    public List<ReportLayoutItem> items;
}
