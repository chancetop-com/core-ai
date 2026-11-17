package ai.core.api.server.agent;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class AgentQualityRunListResponse {
    @Property(name = "runs")
    public List<AgentQualityRunView> runs;
}
