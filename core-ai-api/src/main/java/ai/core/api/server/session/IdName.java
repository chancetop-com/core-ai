package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * A lightweight id+name pair used to carry human-readable names alongside identifiers
 * in session and agent API responses (loaded tools/skills/sub-agents, agent bindings).
 */
public class IdName {
    @NotNull
    @Property(name = "id")
    public String id;

    @NotNull
    @Property(name = "name")
    public String name;

    public IdName() {
    }
}
