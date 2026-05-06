package ai.core.server.trigger.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author stephen
 */
public enum TriggerType {
    @MongoEnumValue("WEBHOOK")
    WEBHOOK,
    @MongoEnumValue("SCHEDULE")
    SCHEDULE
}
