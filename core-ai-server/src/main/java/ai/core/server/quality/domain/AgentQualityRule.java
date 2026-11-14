package ai.core.server.quality.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * Stable logical identity of a publish-quality rule. Revisions are immutable; this document only tracks
 * which revision is current and whether the rule is still in use.
 *
 * @author core-ai
 */
@Collection(name = "agent_quality_rules")
public class AgentQualityRule {
    @Id
    public String id;

    @Field(name = "rule_key")
    public String key;

    @Field(name = "name")
    public String name;

    @Field(name = "category")
    public String category;

    @Field(name = "built_in")
    public Boolean builtIn;

    @Field(name = "latest_revision")
    public Integer latestRevision;

    @Field(name = "lifecycle")
    public QualityRuleLifecycle lifecycle;

    @Field(name = "last_tested_revision")
    public Integer lastTestedRevision;

    @Field(name = "last_tested_at")
    public ZonedDateTime lastTestedAt;

    @Field(name = "last_test_summary")
    public String lastTestSummary;

    @Field(name = "created_by")
    public String createdBy;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_by")
    public String updatedBy;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Field(name = "retired_at")
    public ZonedDateTime retiredAt;
}
