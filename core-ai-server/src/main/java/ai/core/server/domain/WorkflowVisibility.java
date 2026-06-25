package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author Xander
 */
public enum WorkflowVisibility {
    @MongoEnumValue("PRIVATE")
    PRIVATE,
    @MongoEnumValue("PUBLIC")
    PUBLIC
}
