package ai.core.server.comparison.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * @author stephen
 */
public enum ComparisonVoteChoice {
    @MongoEnumValue("A")
    A,

    @MongoEnumValue("B")
    B,

    @MongoEnumValue("TIE")
    TIE,

    @MongoEnumValue("NEITHER")
    NEITHER
}
