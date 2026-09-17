package ai.core.server.memory;

/**
 * Outcome of an explicit remember request: the stored row, or the existing row that already holds the
 * same statement — repeating a remember request must never duplicate knowledge.
 *
 * @author Xander
 */
public record RememberResult(Status status, AgentMemory memory) {
    public static RememberResult created(AgentMemory memory) {
        return new RememberResult(Status.CREATED, memory);
    }

    public static RememberResult alreadyExists(AgentMemory memory) {
        return new RememberResult(Status.ALREADY_EXISTS, memory);
    }

    public enum Status {
        CREATED,
        ALREADY_EXISTS
    }
}
