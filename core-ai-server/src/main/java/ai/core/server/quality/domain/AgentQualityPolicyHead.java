package ai.core.server.quality.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * Single-document activation pointer. Switching the active policy is one conditional write, never a loop
 * of per-rule toggles that could leave a half-applied policy visible.
 *
 * @author core-ai
 */
@Collection(name = "agent_quality_policy_heads")
public class AgentQualityPolicyHead {
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
