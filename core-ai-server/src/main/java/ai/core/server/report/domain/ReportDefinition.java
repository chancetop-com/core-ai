package ai.core.server.report.domain;

import java.time.ZonedDateTime;

/**
 * @author core-ai
 */
public class ReportDefinition {
    public String id;
    public String userId;
    public String title;
    public String description;
    public long revision;
    public int specVersion;
    public int engineVersion;
    public ReportSpec spec;
    public String specJson;
    public String datasetId;
    public ReportDependency dependency;
    public String dependencyJson;
    public String originSessionId;
    public String originAgentId;
    public ZonedDateTime createdAt;
    public ZonedDateTime updatedAt;
}
