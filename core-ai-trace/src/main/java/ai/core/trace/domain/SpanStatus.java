package ai.core.trace.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author Xander
 */
public enum SpanStatus {
    @MongoEnumValue("OK")
    OK,
    @MongoEnumValue("ERROR")
    ERROR
}
