package ai.core.server.memory.experiment;

import core.framework.mongo.MongoEnumValue;

/**
 * Memory ranking strategy for injection.
 *
 * @author stephen
 */
public enum RankingStrategy {
    @MongoEnumValue("SEMANTIC")
    SEMANTIC,     // embedding-based semantic search
    @MongoEnumValue("BM25")
    BM25,         // keyword-based retrieval
    @MongoEnumValue("RECENCY")
    RECENCY,      // most recently used first
    @MongoEnumValue("IMPORTANCE")
    IMPORTANCE,   // per-memory importance score
    @MongoEnumValue("HYBRID")
    HYBRID,       // combines semantic + recency + importance
    @MongoEnumValue("RANDOM")
    RANDOM;       // random sampling (control group)

    public static RankingStrategy fromValue(String value) {
        for (var s : values()) {
            if (s.name().equalsIgnoreCase(value)) return s;
        }
        return SEMANTIC;
    }
}
