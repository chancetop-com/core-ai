package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author stephen
 */
public enum RunStatus {
    @MongoEnumValue("PENDING")
    PENDING,
    @MongoEnumValue("RUNNING")
    RUNNING,
    @MongoEnumValue("COMPLETED")
    COMPLETED,
    @MongoEnumValue("FAILED")
    FAILED,
    @MongoEnumValue("TIMEOUT")
    TIMEOUT,
    @MongoEnumValue("CANCELLED")
    CANCELLED
}
