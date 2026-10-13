package ai.core.api.server.comparison;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * One comparison test. Variant configs are only present for the creator/admin; participants get a
 * blind view (labels only) so they cannot tell which version they preferred.
 *
 * @author stephen
 */
public class AgentComparisonView {
    @Property(name = "id")
    public String id;

    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "agent_name")
    public String agentName;

    @Property(name = "status")
    public String status;

    @Property(name = "created_by")
    public String createdBy;

    @Property(name = "participant_user_ids")
    public List<String> participantUserIds;

    @Property(name = "blind")
    public Boolean blind;

    @Property(name = "variant_a")
    public ComparisonVariantView variantA;

    @Property(name = "variant_b")
    public ComparisonVariantView variantB;

    /** Source saved versions of each side; null = the agent's current published config (variant A) respectively a copy of it. */
    @Property(name = "version_a_id")
    public String versionAId;

    @Property(name = "version_b_id")
    public String versionBId;

    @Property(name = "pairs")
    public List<ComparisonSessionPairView> pairs;

    @Property(name = "votes")
    public List<ComparisonVoteView> votes;

    @Property(name = "counts")
    public ComparisonCountsView counts;

    @Property(name = "budget_usd")
    public Double budgetUsd;

    /** true when the live agent was edited after the test started; adopting would overwrite those edits. */
    @Property(name = "agent_changed")
    public Boolean agentChanged;

    @Property(name = "started_at")
    public ZonedDateTime startedAt;

    @Property(name = "ended_at")
    public ZonedDateTime endedAt;

    @Property(name = "adopted_variant")
    public String adoptedVariant;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
