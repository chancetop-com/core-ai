package ai.core.server.costalert;

import core.framework.mongo.MongoEnumValue;

/**
 * Aggregation scope of a cost alert rule.
 *
 * @author stephen
 */
public enum CostAlertScope {
    @MongoEnumValue("global")
    GLOBAL,
    @MongoEnumValue("user")
    USER,
    @MongoEnumValue("agent")
    AGENT
}
