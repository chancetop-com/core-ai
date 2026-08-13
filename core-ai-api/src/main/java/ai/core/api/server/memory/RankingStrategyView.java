package ai.core.api.server.memory;

import core.framework.api.json.Property;

/**
 * JSON view mirror of the ranking strategy enum. Separate from the entity enum
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
    RANDOM
}
