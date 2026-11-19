package ai.core.server.skill.quality.domain;

import ai.core.server.quality.domain.QualityRuleBinding;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Immutable snapshot of which skill rule revisions were enforced, and how, for every skill write.
 *
 * @author core-ai
 */
@Collection(name = "skill_quality_policy_revisions")
public class SkillQualityPolicyRevision {
    @Id
    public String id;

    @Field(name = "revision")
    public Integer revision;

    @Field(name = "scope")
    public String scope;

    @Field(name = "bindings")
    public List<QualityRuleBinding> bindings;

    /** Skill names or namespace/name pairs that skip the gate entirely; audited with the policy revision. */
    @Field(name = "exemptions")
    public List<String> exemptions;

    @Field(name = "change_reason")
    public String changeReason;

    @Field(name = "activated_by")
    public String activatedBy;

    @Field(name = "activated_at")
    public ZonedDateTime activatedAt;

    @Field(name = "generation")
    public Integer generation;
}
