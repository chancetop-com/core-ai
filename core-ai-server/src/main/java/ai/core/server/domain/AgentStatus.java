package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author stephen
 */
public enum AgentStatus {
    @MongoEnumValue("DRAFT")
    DRAFT,
    @MongoEnumValue("PUBLISHED")
    PUBLISHED
}
