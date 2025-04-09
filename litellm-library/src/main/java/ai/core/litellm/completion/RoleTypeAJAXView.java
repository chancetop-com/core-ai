package ai.core.litellm.completion;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public enum RoleTypeAJAXView {
    @Property(name = "user")
    USER,
    @Property(name = "assistant")
    ASSISTANT,
    @Property(name = "system")
    SYSTEM,
    @Property(name = "tool")
    TOOL
}
