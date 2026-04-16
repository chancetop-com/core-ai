package ai.core.api.server.session;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public enum SandboxEventType {
    @Property(name = "creating")
    CREATING,
    @Property(name = "ready")
    READY,
    @Property(name = "error")
    ERROR,
    @Property(name = "replacing")
    REPLACING,
    @Property(name = "terminated")
    TERMINATED
}
