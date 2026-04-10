package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * Enum representing the source type of a tool reference.
 * Used in ToolRef to indicate where to look up the tool.
 */
public enum ToolSourceType {
    @MongoEnumValue("BUILTIN")
    BUILTIN,
    @MongoEnumValue("MCP")
    MCP,
    @MongoEnumValue("API")
    API,
    @MongoEnumValue("AGENT")
    AGENT
}