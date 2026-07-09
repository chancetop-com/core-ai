package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author stephen
 */
public enum NotificationType {
    @MongoEnumValue("PAUSE")
    PAUSE,
    @MongoEnumValue("TERMINATE")
    TERMINATE,
    @MongoEnumValue("AGENT_NOTIFICATION")
    AGENT_NOTIFICATION
}
