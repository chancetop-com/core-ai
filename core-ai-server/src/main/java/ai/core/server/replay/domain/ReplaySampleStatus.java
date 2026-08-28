package ai.core.server.replay.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * Status of a single replay sample (one execution of a variant request).
 *
 * @author stephen
 */
public enum ReplaySampleStatus {
    @MongoEnumValue("running")
    RUNNING,
    @MongoEnumValue("completed")
    COMPLETED,
    @MongoEnumValue("error")
    ERROR,
    @MongoEnumValue("cancelled")
    CANCELLED
}
