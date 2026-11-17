package ai.core.tool.wait;

/**
 * Whether the current host allows a tool call to park its turn until an async task completes.
 * Disabled by default: hosts that cannot guarantee the waiting thread is not an execution slot
 * (the server run pool) must opt in explicitly.
 *
 * @author stephen
 */
public enum WaitCapability {
    ENABLED,
    DISABLED
}
