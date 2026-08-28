package ai.core.server.replay.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * Aggregate status of a replay run, derived from its samples.
 * All samples cancelled collapses to CANCELLED (never PARTIAL).
 *
 * @author stephen
 */
public enum ReplayRunStatus {
    @MongoEnumValue("running")
    RUNNING,
    @MongoEnumValue("completed")
    COMPLETED,
    @MongoEnumValue("partial")
    PARTIAL,
    @MongoEnumValue("error")
    ERROR,
    @MongoEnumValue("cancelled")
    CANCELLED
}
