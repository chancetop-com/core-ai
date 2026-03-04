package ai.core.api.server.run;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * @author stephen
 */
public class AgentRunView {
    @NotNull
    @Property(name = "id")
    public String id;

    @NotNull
    @Property(name = "agent_id")
    public String agentId;

    @NotNull
    @Property(name = "triggered_by")
    public String triggeredBy;

    @NotNull
    @Property(name = "status")
    public String status;

    @Property(name = "input")
    public String input;

    @Property(name = "output")
    public String output;

    @Property(name = "error")
    public String error;

    @Property(name = "token_usage")
    public Map<String, Long> tokenUsage;

    @Property(name = "started_at")
    public ZonedDateTime startedAt;

    @Property(name = "completed_at")
    public ZonedDateTime completedAt;
}
