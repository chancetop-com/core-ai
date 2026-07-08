package ai.core.server.memory.experiment;

import core.framework.api.json.Property;

import java.util.List;

/**
 * List-item view for experiment configs (summary only, no full detail).
 *
 * @author stephen
 */
public class ExperimentConfigListItemView {
    @Property(name = "id")
    public String id;

    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "enabled")
    public Boolean enabled;

    @Property(name = "enabled_layers")
    public List<MemoryLayerView> enabledLayers;

    @Property(name = "ranking_strategy")
    public RankingStrategyView rankingStrategy;

    @Property(name = "top_k")
    public Integer topK;

    @Property(name = "injection_probability")
    public Double injectionProbability;
}
