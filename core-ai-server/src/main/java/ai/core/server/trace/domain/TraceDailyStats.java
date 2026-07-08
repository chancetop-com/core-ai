package ai.core.server.trace.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * Pre-aggregated daily token/cost stats per user and agent.
 * Populated by TraceDailyMaintenanceJob.
 *
 * @author cyril
 */
@Collection(name = "trace_daily_stats")
public class TraceDailyStats {
    @Id
    public String id;

    @Field(name = "user_id")
    public String userId;

    @Field(name = "agent_id")
    public String agentId;

    @Field(name = "date")
    public ZonedDateTime date;

    @Field(name = "input_tokens")
    public Long inputTokens;

    @Field(name = "output_tokens")
    public Long outputTokens;

    @Field(name = "total_tokens")
    public Long totalTokens;

    @Field(name = "cached_tokens")
    public Long cachedTokens;

    @Field(name = "cost_usd")
    public Double costUsd;

    @Field(name = "call_count")
    public Long callCount;

    @Field(name = "avg_total_tokens")
    public Double avgTotalTokens;

    @Field(name = "avg_cost_usd")
    public Double avgCostUsd;

    @Field(name = "p90_total_tokens")
    public Double p90TotalTokens;

    @Field(name = "p90_cost_usd")
    public Double p90CostUsd;

    @Field(name = "session_count")
    public Long sessionCount;
}
