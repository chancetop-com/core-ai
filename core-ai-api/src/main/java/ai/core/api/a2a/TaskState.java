package ai.core.api.a2a;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public enum TaskState {
    @Property(name = "TASK_STATE_UNSPECIFIED")
    UNKNOWN,
    @Property(name = "TASK_STATE_SUBMITTED")
    SUBMITTED,
    @Property(name = "TASK_STATE_WORKING")
    WORKING,
    @Property(name = "TASK_STATE_INPUT_REQUIRED")
    INPUT_REQUIRED,
    @Property(name = "TASK_STATE_COMPLETED")
    COMPLETED,
    @Property(name = "TASK_STATE_CANCELED")
    CANCELED,
    @Property(name = "TASK_STATE_FAILED")
    FAILED,
    @Property(name = "TASK_STATE_REJECTED")
    REJECTED,
    @Property(name = "TASK_STATE_AUTH_REQUIRED")
    AUTH_REQUIRED
}
