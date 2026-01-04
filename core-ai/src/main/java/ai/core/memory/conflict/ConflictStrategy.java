package ai.core.memory.conflict;

/**
 * Strategy for resolving memory conflicts.
 *
 * @author xander
 */
public enum ConflictStrategy {

    /**
     * Keep only the newest record, discard older ones.
     * Simple and fast, but may lose important historical context.
     */
    KEEP_LATEST,

    /**
     * Use LLM to merge all conflicting records into one.
     * Best quality but requires LLM call. This is the default strategy.
     */
    LLM_MERGE,

    /**
     * Keep the record with the highest importance score.
     * Useful when importance is well-calibrated.
     */
    KEEP_MOST_IMPORTANT
}
