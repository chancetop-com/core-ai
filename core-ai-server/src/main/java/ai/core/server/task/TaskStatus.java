package ai.core.server.task;

import core.framework.mongo.MongoEnumValue;

/**
 * Lifecycle states for background task records.
 *
 * @author cyril
 */
public enum TaskStatus {
    @MongoEnumValue("RUNNING")
    RUNNING,
    @MongoEnumValue("SUCCESS")
    SUCCESS,
    @MongoEnumValue("FAILED")
    FAILED
}
