package ai.core.server.skill.quality.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * Single-document activation pointer for the skill policy. Switching policies is one conditional write.
 *
 * @author core-ai
 */
@Collection(name = "skill_quality_policy_heads")
public class SkillQualityPolicyHead {
    @Id
    public String id;

    @Field(name = "scope")
    public String scope;

    @Field(name = "active_revision")
    public Integer activeRevision;

    @Field(name = "generation")
    public Integer generation;

    @Field(name = "updated_by")
    public String updatedBy;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
