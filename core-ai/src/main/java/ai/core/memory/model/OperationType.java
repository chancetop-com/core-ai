package ai.core.memory.model;

/**
 * Memory operation type for consolidation phase.
 *
 * @author xander
 */
public enum OperationType {
    /**
     * Add new memory to store.
     */
    ADD,

    /**
     * Update existing memory with new information.
     */
    UPDATE,

    /**
     * Delete existing memory (contradicted by new info).
     */
    DELETE,

    /**
     * No operation (duplicate or irrelevant).
     */
    NOOP
}
