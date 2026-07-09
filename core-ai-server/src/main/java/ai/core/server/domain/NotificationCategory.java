package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author stephen
 */
public enum NotificationCategory {
    @MongoEnumValue("AGENT")
    AGENT,
    @MongoEnumValue("SYSTEM")
    SYSTEM
}
