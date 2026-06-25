package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author Xander
 */
public enum WorkflowDefinitionStatus {
    @MongoEnumValue("ACTIVE")
    ACTIVE,
    @MongoEnumValue("ARCHIVED")
    ARCHIVED,
    @MongoEnumValue("DISABLED")
    DISABLED
}
