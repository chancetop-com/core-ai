package ai.core.server.apiuser;

import core.framework.mongo.Field;

/**
 * Aggregation view for API user daily usage: $group emits the UTC day string as _id.
 *
 * @author core-ai
 */
public class ApiUserDailyUsageRow {
    @Field(name = "_id")
    public String day;

    @Field(name = "totalTokens")
    public Long totalTokens;

    @Field(name = "inputTokens")
    public Long inputTokens;

    @Field(name = "outputTokens")
    public Long outputTokens;

    @Field(name = "cachedTokens")
    public Long cachedTokens;

    @Field(name = "costUsd")
    public Double costUsd;

    @Field(name = "callCount")
    public Long callCount;
}
