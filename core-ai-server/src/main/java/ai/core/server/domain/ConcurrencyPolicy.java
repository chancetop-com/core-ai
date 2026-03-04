package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author stephen
 */
public enum ConcurrencyPolicy {
    @MongoEnumValue("SKIP")
    SKIP,
    @MongoEnumValue("QUEUE")
    QUEUE,
    @MongoEnumValue("PARALLEL")
    PARALLEL
}
