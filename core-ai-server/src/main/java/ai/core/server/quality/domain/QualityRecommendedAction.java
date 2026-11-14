package ai.core.server.quality.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author core-ai
 */
public enum QualityRecommendedAction {
    @MongoEnumValue("EDIT_INPUT")
    EDIT_INPUT,
    @MongoEnumValue("RETRY")
    RETRY,
    @MongoEnumValue("CONTACT_ADMIN")
    CONTACT_ADMIN,
    @MongoEnumValue("REFRESH")
    REFRESH
}
