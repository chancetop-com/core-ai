package ai.core.memory.model;

/**
 * Result of memory consolidation operation.
 *
 * @author xander
 */
public record MemoryOperation(
    OperationType type,
    String existingId,
    String reason
) {
    public static MemoryOperation add() {
        return new MemoryOperation(OperationType.ADD, null, "New unique information");
    }

    public static MemoryOperation add(String reason) {
        return new MemoryOperation(OperationType.ADD, null, reason);
    }

    public static MemoryOperation update(String existingId, String reason) {
        return new MemoryOperation(OperationType.UPDATE, existingId, reason);
    }

    public static MemoryOperation delete(String existingId, String reason) {
        return new MemoryOperation(OperationType.DELETE, existingId, reason);
    }

    public static MemoryOperation noop(String reason) {
        return new MemoryOperation(OperationType.NOOP, null, reason);
    }
}
