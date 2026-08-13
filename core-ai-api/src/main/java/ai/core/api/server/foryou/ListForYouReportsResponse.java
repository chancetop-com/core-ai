package ai.core.api.server.foryou;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListForYouReportsResponse {
    @Property(name = "reports")
    public List<ForYouReportView> reports;
}
