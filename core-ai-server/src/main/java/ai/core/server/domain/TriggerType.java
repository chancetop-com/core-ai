package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author stephen
 */
public enum TriggerType {
    @MongoEnumValue("SCHEDULE")
    SCHEDULE,
    @MongoEnumValue("MANUAL")
    MANUAL,
    @MongoEnumValue("API")
    API
}
