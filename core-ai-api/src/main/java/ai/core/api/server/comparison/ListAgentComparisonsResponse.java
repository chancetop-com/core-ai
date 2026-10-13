package ai.core.api.server.comparison;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListAgentComparisonsResponse {
    @Property(name = "comparisons")
    public List<AgentComparisonView> comparisons;
}
