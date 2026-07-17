package ai.core.server.trace.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * Pre-aggregated daily token/cost stats with fine-grained grouping for admin analytics dashboard.
 * Grouped by (user_id, agent_id, source, model, provider_id, date).
 * Populated by TraceDailyMaintenanceService alongside trace_daily_stats.
 *
 * @author core-ai
 */
@Collection(name = "analytics_daily_stats")
public class AnalyticsDailyStats {
    @Id
    public String id;           // "{userId}::{agentId}::{source}::{model}::{providerId}::{date}"

    @Field(name = "user_id")
    public String userId;

    @Field(name = "agent_id")
    public String agentId;

    @Field(name = "agent_name")
    public String agentName;   // denormalized for display

    @Field(name = "source")
    public String source;

    @Field(name = "model")
    public String model;

    @Field(name = "provider_id")
    public String providerId;

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

    @Field(name = "avg_input_tokens")
    public Double avgInputTokens;

    @Field(name = "avg_output_tokens")
    public Double avgOutputTokens;

    @Field(name = "avg_total_tokens")
    public Double avgTotalTokens;

    @Field(name = "avg_cost_usd")
    public Double avgCostUsd;

    @Field(name = "max_total_tokens")
    public Long maxTotalTokens;

    @Field(name = "max_cost_usd")
    public Double maxCostUsd;

    @Field(name = "p90_total_tokens")
    public Double p90TotalTokens;

    @Field(name = "p90_cost_usd")
    public Double p90CostUsd;

    @Field(name = "session_count")
    public Long sessionCount;
}
