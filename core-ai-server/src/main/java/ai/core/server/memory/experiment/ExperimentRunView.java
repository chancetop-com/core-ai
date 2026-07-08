package ai.core.server.memory.experiment;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * JSON view for a single experiment run.
 *
 * @author stephen
 */
public class ExperimentRunView {
    @Property(name = "id")
    public String id;

    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "session_id")
    public String sessionId;

    @Property(name = "run_id")
    public String runId;

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

    @Property(name = "injection_decision")
    public Boolean injectionDecision;

    @Property(name = "injected_memory_ids")
    public List<String> injectedMemoryIds;

    @Property(name = "injected_memory_count")
    public Integer injectedMemoryCount;

    @Property(name = "layer_breakdown")
    public Map<String, Integer> layerBreakdown;

    @Property(name = "prompt_tokens")
    public Long promptTokens;

    @Property(name = "outcome")
    public String outcome;

    @Property(name = "user_rating")
    public Integer userRating;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
