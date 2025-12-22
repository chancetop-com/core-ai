package ai.core.memory.conflict;

/**
 * Strategy for resolving memory conflicts.
 *
 * @author xander
 */
public enum ConflictStrategy {

    /**
     * Keep only the newest record, discard older ones.
     */
    NEWEST_WINS,

    /**
     * Use LLM to merge all conflicting records into one.
     */
    MERGE,

    /**
     * Keep the record with highest importance score.
     */
    IMPORTANCE_BASED,

    /**
     * Prefer newest, but merge if content is complementary.
     * This is the default recommended strategy.
     */
    NEWEST_WITH_MERGE
}
