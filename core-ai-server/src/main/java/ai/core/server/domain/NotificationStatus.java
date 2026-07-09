package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author stephen
 */
public enum NotificationStatus {
    @MongoEnumValue("UNREAD")
    UNREAD,
    @MongoEnumValue("READ")
    READ
}
