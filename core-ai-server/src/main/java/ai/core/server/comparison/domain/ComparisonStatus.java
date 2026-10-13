package ai.core.server.comparison.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author stephen
 */
public enum ComparisonStatus {
    @MongoEnumValue("DRAFT")
    DRAFT,

    @MongoEnumValue("RUNNING")
    RUNNING,

    @MongoEnumValue("ENDED")
    ENDED
}
