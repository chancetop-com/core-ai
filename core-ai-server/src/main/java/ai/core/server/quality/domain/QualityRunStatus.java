package ai.core.server.quality.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author core-ai
 */
public enum QualityRunStatus {
    @MongoEnumValue("STARTED")
    STARTED,
    @MongoEnumValue("COMMITTING")
    COMMITTING,
    @MongoEnumValue("COMPLETED")
    COMPLETED,
    @MongoEnumValue("FAILED")
    FAILED,
    @MongoEnumValue("ABORTED")
    ABORTED,
    @MongoEnumValue("CANCELLED")
    CANCELLED
}
