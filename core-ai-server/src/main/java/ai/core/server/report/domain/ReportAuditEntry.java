package ai.core.server.report.domain;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.ZonedDateTime;

/**
 * @author core-ai
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ReportAuditEntry {
    public String id;
    public String userId;
    public String reportId;
    public String action;
    public String actorUserId;
    public String originSessionId;
    public String originAgentId;
    public Long revision;
    public String specHash;
    public String detailsJson;
    public ZonedDateTime createdAt;
}
