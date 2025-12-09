package ai.core.memory.model;

/**
 * Semantic memory category enumeration.
 *
 * @author xander
 */
public enum SemanticCategory {
    /**
     * Facts: objective information about the user.
     * Example: "User's name is Alex", "User lives in Paris"
     */
    FACT,

    /**
     * Preferences: user's likes and dislikes.
     * Example: "User prefers tea over coffee", "User dislikes horror movies"
     */
    PREFERENCE,

    /**
     * Knowledge: learned information about the user's expertise or interests.
     * Example: "User is a Java developer", "User is learning Python"
     */
    KNOWLEDGE
}
