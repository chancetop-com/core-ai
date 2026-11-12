package ai.core.server.run;

import ai.core.server.domain.RunStatus;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class AgentRunQuery {
    public String agentId;
    public List<String> agentIds;
    public String userId;
    public String scheduleId;
    public List<String> ids;
    public List<String> excludeIds;
    public RunStatus status;
    public List<RunStatus> statuses;
    public ZonedDateTime from;
    public String fromField = "started_at";
    public ZonedDateTime to;
    public boolean hasArtifacts;
    public String sortField = "started_at";
    public boolean ascending;
    public int offset;
    public int limit = 1000;
}
