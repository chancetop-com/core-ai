package ai.core.server.memory.experiment;

import core.framework.api.json.Property;

import java.util.List;

/**
 * JSON view for AgentMemoryExperimentConfig API responses.
 *
 * @author stephen
 */
public class AgentMemoryExperimentConfigView {
    @Property(name = "id")
    public String id;

    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "enabled")
    public Boolean enabled;

    @Property(name = "injection_probability")
    public Double injectionProbability;

    @Property(name = "enabled_layers")
    public List<MemoryLayerView> enabledLayers;

    @Property(name = "top_k")
    public Integer topK;

    @Property(name = "ranking_strategy")
    public RankingStrategyView rankingStrategy;
}
