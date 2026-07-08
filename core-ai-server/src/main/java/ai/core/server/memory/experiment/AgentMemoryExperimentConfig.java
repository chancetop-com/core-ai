package ai.core.server.memory.experiment;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Per-agent experiment configuration: defines HOW memory is injected.
 * Stored in collection "agent_memory_experiment_configs".
 *
 * Four-layer policy:
 *   1. Should Inject  — enabled + injectionProbability (A/B)
 *   2. Which Layers   — enabledLayers
 *   3. Which Memories — topK
 *   4. Ranking        — rankingStrategy
 *
 * @author stephen
 */
@Collection(name = "agent_memory_experiment_configs")
public class AgentMemoryExperimentConfig {
    @Id
    public String id;

    @Field(name = "agent_id")
    public String agentId;

    // ── Layer 1: Should Inject ──
    @Field(name = "enabled")
    public Boolean enabled;

    @Field(name = "injection_probability")
    public Double injectionProbability;

    // ── Layer 2: Which Layers ──
    @Field(name = "enabled_layers")
    public List<MemoryLayer> enabledLayers;

    // ── Layer 3: Which Memories ──
    @Field(name = "top_k")
    public Integer topK;

    // ── Layer 4: Ranking ──
    @Field(name = "ranking_strategy")
    public RankingStrategy rankingStrategy;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
