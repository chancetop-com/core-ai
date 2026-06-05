package ai.core.server.domain;

import core.framework.mongo.MongoEnumValue;

/**
 * The kind of container that opened a scope frame: ITERATION (map over an array) or LOOP (sequential carry).
 *
 * @author Xander
 */
public enum ScopeType {
    @MongoEnumValue("ITERATION")
    ITERATION,
    @MongoEnumValue("LOOP")
    LOOP
}
