package ai.core.api.server.session;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public enum SessionStatus {
    @Property(name = "idle")
    IDLE,
    @Property(name = "running")
    RUNNING,
    @Property(name = "error")
    ERROR
}
