package ai.core.server.trace.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author Xander
 */
public enum TraceStatus {
    @MongoEnumValue("RUNNING")
    RUNNING,
    @MongoEnumValue("COMPLETED")
    COMPLETED,
    @MongoEnumValue("CANCELLED")
    CANCELLED,
    @MongoEnumValue("ERROR")
    ERROR
}
