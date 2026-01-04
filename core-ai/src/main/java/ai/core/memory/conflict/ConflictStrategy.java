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
     * Best quality but requires LLM call.
     */
    LLM_MERGE,

    /**
     * Keep the record with highest importance score.
     * Useful when importance is well-calibrated.
     */
    KEEP_MOST_IMPORTANT,

    /**
     * Smart merge: prefer newest, use LLM merge when beneficial.
     * This is the default recommended strategy.
     */
    SMART_MERGE
}
