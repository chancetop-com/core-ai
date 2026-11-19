package ai.core.server.report.domain;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * @author core-ai
 */
public class HomeReportLayout {
    public String userId;
    public long revision;
    public ZonedDateTime updatedAt;
    public List<HomeReportPin> pins = new ArrayList<>();
}
