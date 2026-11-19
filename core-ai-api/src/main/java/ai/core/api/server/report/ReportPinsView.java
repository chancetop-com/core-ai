package ai.core.api.server.report;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class ReportPinsView {
    @Property(name = "layout_revision")
    public Long layoutRevision;

    @Property(name = "pins")
    public List<ReportPinView> pins;
}
