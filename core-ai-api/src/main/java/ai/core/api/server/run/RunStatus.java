package ai.core.api.server.run;

import core.framework.api.json.Property;

/**
 * @author xander
 */
public enum RunStatus {
    @Property(name = "PENDING")
    PENDING,
    @Property(name = "RUNNING")
    RUNNING,
    @Property(name = "COMPLETED")
    COMPLETED,
    @Property(name = "FAILED")
    FAILED,
    @Property(name = "TIMEOUT")
    TIMEOUT,
    @Property(name = "CANCELLED")
    CANCELLED
}
