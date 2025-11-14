package ai.core.reflection;

/**
 * Represents the current status of an agent's reflection process.
 * <p>
 * Reflection is an iterative self-improvement mechanism where an agent
 * reviews and refines its output through multiple rounds of evaluation
 * until reaching a satisfactory result or meeting a termination condition.
 */
public enum ReflectionStatus {
    /**
     * The reflection process is currently active and ongoing.
     * The agent is in the middle of evaluating or refining its output.
     */
    IN_PROGRESS,

    /**
     * The reflection process completed successfully.
     * The agent's output met the quality criteria or acceptance conditions.
     */
    COMPLETED_SUCCESS,

    /**
     * The reflection process completed because the maximum allowed
     * number of reflection rounds was reached. The current output
     * is the best achieved within the configured limit.
     */
    COMPLETED_MAX_ROUNDS,

    /**
     * The reflection process completed because no further improvement
     * was detected. The agent determined that additional reflection
     * rounds would not meaningfully enhance the output quality.
     */
    COMPLETED_NO_IMPROVEMENT,

    /**
     * The reflection process failed due to an error or exception.
     * The agent was unable to complete the reflection successfully.
     */
    FAILED,

    /**
     * The reflection process was interrupted or cancelled before completion.
     * This may occur due to external termination requests or timeout conditions.
     */
    INTERRUPTED
}
