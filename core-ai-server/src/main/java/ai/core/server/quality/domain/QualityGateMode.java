package ai.core.server.quality.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author core-ai
 */
public enum QualityGateMode {
    @MongoEnumValue("PUBLISH")
    PUBLISH,
    @MongoEnumValue("PRECHECK")
    PRECHECK,
    @MongoEnumValue("WORKFLOW")
    WORKFLOW,
    @MongoEnumValue("FORK")
    FORK
}
