package ai.core.server.domain;

import core.framework.mongo.Field;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Cached cost snapshot stored on the {@link Project} document. Recalculated by the stats refresh
 * job whenever the project is marked dirty (members changed, attribution advanced, analysis ran) —
 * the stats API reads this snapshot instead of aggregating traces per request.
 *
 * @author stephen
 */
public class ProjectStatsData {
    @Field(name = "total_tokens")
    public Long totalTokens;

    @Field(name = "total_cost_usd")
    public Double totalCostUsd;

    @Field(name = "trace_count")
    public Long traceCount;

    @Field(name = "by_agent")
    public List<ProjectStatsItem> byAgent;

    @Field(name = "by_subject")
    public List<ProjectStatsItem> bySubject;

    // per-subject breakdown (totals + by agent) so the subject page reads the same cache
    @Field(name = "subjects")
    public List<ProjectSubjectStats> subjects;

    @Field(name = "computed_at")
    public ZonedDateTime computedAt;
}
