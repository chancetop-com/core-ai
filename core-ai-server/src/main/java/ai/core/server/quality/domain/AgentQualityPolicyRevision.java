package ai.core.server.quality.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Immutable snapshot of "which rule revisions were enforced, and how" for one publish.
 *
 * @author core-ai
 */
@Collection(name = "agent_quality_policy_revisions")
public class AgentQualityPolicyRevision {
    @Id
    public String id;

    @Field(name = "revision")
    public Integer revision;

    @Field(name = "scope")
    public String scope;

    @Field(name = "bindings")
    public List<QualityRuleBinding> bindings;

    @Field(name = "change_reason")
    public String changeReason;

    @Field(name = "activated_by")
    public String activatedBy;

    @Field(name = "activated_at")
    public ZonedDateTime activatedAt;

    @Field(name = "generation")
    public Integer generation;
}
