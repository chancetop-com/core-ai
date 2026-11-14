package ai.core.server.quality.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author core-ai
 */
public enum QualityRuleKind {
    @MongoEnumValue("CODE")
    CODE,
    @MongoEnumValue("DECISION")
    DECISION,
    @MongoEnumValue("HYBRID")
    HYBRID
}
