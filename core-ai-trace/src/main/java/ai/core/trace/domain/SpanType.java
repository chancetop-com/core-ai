package ai.core.trace.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author Xander
 */
public enum SpanType {
    @MongoEnumValue("LLM")
    LLM,
    @MongoEnumValue("AGENT")
    AGENT,
    @MongoEnumValue("TOOL")
    TOOL,
    @MongoEnumValue("FLOW")
    FLOW,
    @MongoEnumValue("GROUP")
    GROUP
}
