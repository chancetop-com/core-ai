package ai.core.server.quality.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author core-ai
 */
public enum QualityGateResult {
    @MongoEnumValue("PASSED")
    PASSED,
    @MongoEnumValue("REJECTED")
    REJECTED,
    @MongoEnumValue("UNAVAILABLE")
    UNAVAILABLE
}
