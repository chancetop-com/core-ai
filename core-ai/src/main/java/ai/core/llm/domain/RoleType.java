package ai.core.llm.domain;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public enum RoleType {
    @Property(name = "user")
    USER,
    @Property(name = "assistant")
    ASSISTANT,
    @Property(name = "system")
    SYSTEM,
    @Property(name = "tool")
    TOOL
}
