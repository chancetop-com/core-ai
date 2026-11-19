package ai.core.server.report.domain;

import java.time.ZonedDateTime;

/**
 * @author core-ai
 */
public class HomeReportPin {
    public String id;
    public String userId;
    public String reportId;
    public int position;
    public String width;
    public ZonedDateTime createdAt;
    public ZonedDateTime updatedAt;
}
