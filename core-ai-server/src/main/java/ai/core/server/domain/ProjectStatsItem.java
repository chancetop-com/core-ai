package ai.core.server.domain;

import core.framework.mongo.Field;

/**
 * One aggregated row of the cached project cost snapshot. {@code groupId} is an agent id when the
 * row belongs to {@code byAgent}, or a subject id when it belongs to {@code bySubject}.
 *
 * @author stephen
 */
public class ProjectStatsItem {
    @Field(name = "group_id")
    public String groupId;

    @Field(name = "name")
    public String name;

    @Field(name = "total_tokens")
    public Long totalTokens;

    @Field(name = "total_cost_usd")
    public Double totalCostUsd;

    @Field(name = "trace_count")
    public Long traceCount;
}
