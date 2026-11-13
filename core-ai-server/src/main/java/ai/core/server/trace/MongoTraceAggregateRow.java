package ai.core.server.trace;

import core.framework.mongo.Field;

/**
 * Mongo result shape for {@link TraceAggregation}; the group key always lands in {@code _id}.
 *
 * @author stephen
 */
public class MongoTraceAggregateRow {
    @Field(name = "_id")
    public String group;

    @Field(name = "first_value")
    public String firstValue;

    @Field(name = "input_tokens")
    public Long inputTokens;

    @Field(name = "output_tokens")
    public Long outputTokens;

    @Field(name = "total_tokens")
    public Long totalTokens;

    @Field(name = "cached_tokens")
    public Long cachedTokens;

    @Field(name = "count")
    public Long count;

    @Field(name = "cost_usd")
    public Double costUsd;
}
