package ai.core.server.comparison.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * One agent version comparison test: two frozen config variants plus governance fields.
 *
 * @author stephen
 */
@Collection(name = "agent_comparisons")
public class AgentComparison {
    @Id
    public String id;

    @Field(name = "agent_id")
    public String agentId;

    @Field(name = "agent_name")
    public String agentName;

    /** forkedFrom of the live agent at freeze time — variant sessions keep identity-injection semantics. */
    @Field(name = "agent_forked_from")
    public String agentForkedFrom;

    /**
     * publishedAt of the live agent when the test started. A mismatch at adopt time means the agent was
     * edited mid-test and adopting a variant would overwrite newer changes.
     */
    @Field(name = "agent_published_at")
    public ZonedDateTime agentPublishedAt;

    @Field(name = "status")
    public ComparisonStatus status;

    @Field(name = "variant_a")
    public ComparisonVariant variantA;

    @Field(name = "variant_b")
    public ComparisonVariant variantB;

    /** Saved version feeding variant A; null = the agent's current published config (resolved at start). */
    @Field(name = "version_a_id")
    public String versionAId;

    /** Saved version feeding variant B; null = a copy of variant A's source. */
    @Field(name = "version_b_id")
    public String versionBId;

    @Field(name = "participant_user_ids")
    public List<String> participantUserIds;

    @Field(name = "created_by")
    public String createdBy;

    @Field(name = "started_at")
    public ZonedDateTime startedAt;

    @Field(name = "ended_at")
    public ZonedDateTime endedAt;

    /** "a" | "b" | null (abandoned without adopting). */
    @Field(name = "adopted_variant")
    public String adoptedVariant;

    @Field(name = "budget_usd")
    public Double budgetUsd;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
