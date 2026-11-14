package ai.core.server.memory.experiment;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * The layer list is JSON text: core-ng's json columns only take beans or lists of value classes, and
 * this keeps the enum list independent from the column model.
 *
 * @author stephen
 */
public class PgMemoryExperimentConfigRow {
    @Column(name = "id")
    public String id;

    @Column(name = "agent_id")
    public String agentId;

    @Column(name = "enabled")
    public Boolean enabled;

    @Column(name = "injection_probability")
    public Double injectionProbability;

    @Column(name = "enabled_layers")
    public String enabledLayers;

    @Column(name = "top_k")
    public Integer topK;

    @Column(name = "ranking_strategy")
    public String rankingStrategy;

    @Column(name = "injection_mode")
    public String injectionMode;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;
}
