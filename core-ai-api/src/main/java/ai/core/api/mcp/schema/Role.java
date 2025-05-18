package ai.core.api.mcp.schema;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public enum Role {
    @Property(name = "user")
    USER,
    @Property(name = "assistant")
    ASSISTANT
}
