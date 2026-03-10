package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author stephen
 */
public enum DefinitionType {
    @MongoEnumValue("AGENT")
    AGENT,
    @MongoEnumValue("LLM_CALL")
    LLM_CALL
}
