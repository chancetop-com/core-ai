package ai.core.api.server.run;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListRunsResponse {
    @Property(name = "runs")
    public List<AgentRunView> runs;

    @Property(name = "total")
    public Long total;
}
