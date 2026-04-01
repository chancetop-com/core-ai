package ai.core.api.server.run;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.Map;

/**
 * @author Xander
 */
public class AgentCallResponse {
    @NotNull
    @Property(name = "output")
    public String output;

    @Property(name = "token_usage")
    public Map<String, Long> tokenUsage;

    @Property(name = "run_id")
    public String runId;
}
