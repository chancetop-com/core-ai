package ai.core.server.memory.experiment;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Per-run experiment record: captures the policy applied and which memories
 * were injected for a single agent run.
 *
 * Outcome is patched later when feedback arrives.
 *
 * @author stephen
 */
@Collection(name = "agent_memory_experiment_runs")
public class AgentMemoryExperimentRun {
    @Id
    public String id;

    @Field(name = "agent_id")
    public String agentId;

    @Field(name = "session_id")
    public String sessionId;

    @Field(name = "run_id")
    public String runId;

    // ── Policy snapshot (what was actually applied) ──
    @Field(name = "enabled")
    public Boolean enabled;

    @Field(name = "enabled_layers")
    public List<MemoryLayer> enabledLayers;

    @Field(name = "ranking_strategy")
    public RankingStrategy rankingStrategy;

    @Field(name = "top_k")
    public Integer topK;

    @Field(name = "injection_probability")
    public Double injectionProbability;

    @Field(name = "injection_decision")
    public Boolean injectionDecision;

    // ── What was injected ──
    @Field(name = "injected_memory_ids")
    public List<String> injectedMemoryIds;

    @Field(name = "injected_memory_count")
    public Integer injectedMemoryCount;

    /** layer name → count of memories from that layer */
    @Field(name = "layer_breakdown")
    public Map<String, Integer> layerBreakdown;

    @Field(name = "prompt_tokens")
    public Long promptTokens;

    // ── Attribution (per-memory, set later by LLM judge) ──
    @Field(name = "attributions")
    public List<MemoryAttribution> attributions;

    // ── Outcome (patched from feedback) ──
    @Field(name = "outcome")
    public String outcome;

    @Field(name = "user_rating")
    public Integer userRating;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
