package ai.core.server.domain;

import core.framework.mongo.Field;

import java.util.List;

/**
 * Cached per-subject cost breakdown (totals + by agent), stored inside {@link ProjectStatsData}.
 *
 * @author stephen
 */
public class ProjectSubjectStats {
    @Field(name = "subject_id")
    public String subjectId;

    @Field(name = "total_tokens")
    public Long totalTokens;

    @Field(name = "total_cost_usd")
    public Double totalCostUsd;

    @Field(name = "trace_count")
    public Long traceCount;

    @Field(name = "by_agent")
    public List<ProjectStatsItem> byAgent;
}
