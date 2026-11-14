package ai.core.server.memory.experiment;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgMemoryExperimentRunRow {
    @Column(name = "id")
    public String id;

    @Column(name = "agent_id")
    public String agentId;

    @Column(name = "session_id")
    public String sessionId;

    @Column(name = "run_id")
    public String runId;

    @Column(name = "enabled")
    public Boolean enabled;

    @Column(name = "enabled_layers")
    public String enabledLayers;

    @Column(name = "ranking_strategy")
    public String rankingStrategy;

    @Column(name = "top_k")
    public Integer topK;

    @Column(name = "injection_probability")
    public Double injectionProbability;

    @Column(name = "injection_mode")
    public String injectionMode;

    @Column(name = "injection_decision")
    public Boolean injectionDecision;

    @Column(name = "injected_memory_ids")
    public String injectedMemoryIds;

    @Column(name = "injected_memory_count")
    public Integer injectedMemoryCount;

    @Column(name = "layer_breakdown")
    public String layerBreakdown;

    @Column(name = "prompt_tokens")
    public Long promptTokens;

    @Column(name = "attributions")
    public String attributions;

    @Column(name = "outcome")
    public String outcome;

    @Column(name = "user_rating")
    public Integer userRating;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;
}
