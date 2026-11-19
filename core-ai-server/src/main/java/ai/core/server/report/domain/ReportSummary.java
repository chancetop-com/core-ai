package ai.core.server.report.domain;

import java.time.ZonedDateTime;

/**
 * @author core-ai
 */
public class ReportSummary {
    public String id;
    public String title;
    public long revision;
    public String datasetId;
    public String datasetName;
    public String displayType;
    public String pinId;
    public String pinWidth;
    public ZonedDateTime updatedAt;
}
