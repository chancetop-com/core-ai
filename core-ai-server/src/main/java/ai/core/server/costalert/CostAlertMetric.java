package ai.core.server.costalert;

import core.framework.mongo.MongoEnumValue;

/**
 * Metric evaluated by a cost alert rule.
 *
 * @author stephen
 */
public enum CostAlertMetric {
    @MongoEnumValue("cost_usd")
    COST_USD,
    @MongoEnumValue("total_tokens")
    TOTAL_TOKENS,
    @MongoEnumValue("call_count")
    CALL_COUNT
}
