package ai.core.agent.handoff;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public enum HandoffType {
    @Property(name = "AUTO")
    AUTO,
    @Property(name = "DIRECT")
    DIRECT,
    @Property(name = "HYBRID")
    HYBRID,
    @Property(name = "MANUAL")
    MANUAL
}
