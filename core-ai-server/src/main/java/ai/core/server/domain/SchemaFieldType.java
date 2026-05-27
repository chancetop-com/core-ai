package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author stephen
 */
public enum SchemaFieldType {
    @MongoEnumValue("NUMBER")
    NUMBER,
    @MongoEnumValue("STRING")
    STRING,
    @MongoEnumValue("BOOLEAN")
    BOOLEAN
}
