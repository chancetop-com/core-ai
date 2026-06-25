package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author Xander
 */
public enum WorkflowVersionStatus {
    @MongoEnumValue("ACTIVE")
    ACTIVE,
    @MongoEnumValue("DISABLED")
    DISABLED
}
