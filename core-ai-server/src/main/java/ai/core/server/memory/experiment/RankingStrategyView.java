package ai.core.server.memory.experiment;

import core.framework.api.json.Property;

/**
 * JSON view mirror of {@link RankingStrategy}. Separate from the entity enum
 * because core-ng forbids @MongoEnumValue and @Property on the same class.
 *
 * @author stephen
 */
public enum RankingStrategyView {
    @Property(name = "SEMANTIC")
    SEMANTIC,
    @Property(name = "BM25")
    BM25,
    @Property(name = "RECENCY")
    RECENCY,
    @Property(name = "IMPORTANCE")
    IMPORTANCE,
    @Property(name = "HYBRID")
    HYBRID,
    @Property(name = "RANDOM")
    RANDOM;

    public static RankingStrategyView from(RankingStrategy strategy) {
        return valueOf(strategy.name());
    }

    public RankingStrategy toEntity() {
        return RankingStrategy.valueOf(name());
    }
}
