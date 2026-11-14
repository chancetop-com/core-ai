package ai.core.server.quality.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author core-ai
 */
public enum QualityIssueStatus {
    @MongoEnumValue("PASS")
    PASS,
    @MongoEnumValue("FAIL")
    FAIL,
    @MongoEnumValue("INDETERMINATE")
    INDETERMINATE,
    @MongoEnumValue("ERROR")
    ERROR,
    @MongoEnumValue("SKIPPED")
    SKIPPED
}
