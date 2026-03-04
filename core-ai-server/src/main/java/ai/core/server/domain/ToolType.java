package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author stephen
 */
public enum ToolType {
    @MongoEnumValue("MCP")
    MCP,
    @MongoEnumValue("API")
    API,
    @MongoEnumValue("BUILTIN")
    BUILTIN
}
