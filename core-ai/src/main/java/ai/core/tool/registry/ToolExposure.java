package ai.core.tool.registry;

/**
 * Controls where a tool is exposed to the model.
 * Replaces the binary {@code llmVisible} boolean with three granular levels.
 *
 * @author Lim Chen
 */
public enum ToolExposure {
    /**
     * Tool is visible in the model's tool list and callable.
     */
    DIRECT,
    /**
     * Tool is registered for later discovery via tool_search but omitted
     * from the initial model-visible tool list.
     */
    DEFERRED,
    /**
     * Tool is registered for dispatch only — the model cannot see or call it
     * directly, but the system can execute it internally.
     */
    HIDDEN
}
