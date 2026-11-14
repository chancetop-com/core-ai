package ai.core.server.quality.domain;

import core.framework.mongo.Field;

/**
 * One (rule revision, enforcement) pair inside a policy. Plain nested value, not a collection.
 *
 * @author core-ai
 */
public class QualityRuleBinding {
    @Field(name = "rule_id")
    public String ruleId;

    @Field(name = "rule_revision")
    public Integer ruleRevision;

    @Field(name = "enforcement")
    public QualityEnforcement enforcement;
}
