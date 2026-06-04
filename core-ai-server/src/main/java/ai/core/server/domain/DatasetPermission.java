package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author stephen
 */
public enum DatasetPermission {
    @MongoEnumValue("READ")
    READ,
    @MongoEnumValue("WRITE")
    WRITE,
    @MongoEnumValue("FULL")
    FULL
}
