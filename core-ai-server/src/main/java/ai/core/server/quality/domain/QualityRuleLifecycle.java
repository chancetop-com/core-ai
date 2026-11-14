package ai.core.server.quality.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author core-ai
 */
public enum QualityRuleLifecycle {
    @MongoEnumValue("DRAFT")
    DRAFT,
    @MongoEnumValue("TESTED")
    TESTED,
    @MongoEnumValue("SHADOW")
    SHADOW,
    @MongoEnumValue("ENFORCING")
    ENFORCING,
    @MongoEnumValue("RETIRED")
    RETIRED
}
