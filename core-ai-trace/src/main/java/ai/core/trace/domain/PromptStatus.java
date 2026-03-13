package ai.core.trace.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author Xander
 */
public enum PromptStatus {
    @MongoEnumValue("DRAFT")
    DRAFT,
    @MongoEnumValue("PUBLISHED")
    PUBLISHED,
    @MongoEnumValue("ARCHIVED")
    ARCHIVED
}
