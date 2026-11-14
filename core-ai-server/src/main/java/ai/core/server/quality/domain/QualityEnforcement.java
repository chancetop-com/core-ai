package ai.core.server.quality.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author core-ai
 */
public enum QualityEnforcement {
    @MongoEnumValue("BLOCK")
    BLOCK,
    @MongoEnumValue("WARN")
    WARN,
    @MongoEnumValue("SHADOW")
    SHADOW
}
