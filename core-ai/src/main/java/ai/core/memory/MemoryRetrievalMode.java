package ai.core.memory;

/**
 * Defines how long-term memory is retrieved during agent execution.
 *
 * @author xander
 */
public enum MemoryRetrievalMode {
    /**
     * Automatically retrieve relevant memories at the start of each query.
     * Memory context is injected into the prompt before LLM call.
     * Best for: Simple use cases where memory should always be considered.
     */
    AUTO,

    /**
     * Agent decides when to search memory using SearchMemoryTool.
     * Similar to langmem's approach - agent has control over memory queries.
     * Best for: Complex scenarios where selective memory retrieval is preferred.
     */
    TOOL,

    /**
     * Disable memory retrieval (extraction still happens if enabled).
     */
    DISABLED
}
