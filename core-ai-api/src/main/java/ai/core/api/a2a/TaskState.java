package ai.core.api.a2a;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public enum TaskState {
    @Property(name = "submitted")
    SUBMITTED,
    @Property(name = "working")
    WORKING,
    @Property(name = "input-required")
    INPUT_REQUIRED,
    @Property(name = "completed")
    COMPLETED,
    @Property(name = "canceled")
    CANCELED,
    @Property(name = "failed")
    FAILED
}
