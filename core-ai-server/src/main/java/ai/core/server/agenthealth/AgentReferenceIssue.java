package ai.core.server.agenthealth;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * One active or historical broken-reference episode for an agent. This row drives notification dedupe
 * and audit only; the list badge is derived from the current catalogs and never reads this record.
 *
 * @author core-ai
 */
public class AgentReferenceIssue {
    public String id;
    public String agentId;
    public String agentOwnerId;
    public String agentName;
    public String refType;
    public String refId;
    public String refName;
    public String reason;
    public List<String> sources;
    public ZonedDateTime firstDetectedAt;
    public ZonedDateTime lastSeenAt;
    public ZonedDateTime notifiedAt;
    public ZonedDateTime resolvedAt;
    public String resolution;
}
