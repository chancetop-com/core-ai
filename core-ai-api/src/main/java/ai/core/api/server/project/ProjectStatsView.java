package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Cost aggregates over the project's traces, read from the cached snapshot.
 *
 * @author stephen
 */
public class ProjectStatsView {
    @Property(name = "trace_count")
    public Long traceCount;

    @Property(name = "total_tokens")
    public Long totalTokens;

    @Property(name = "total_cost_usd")
    public Double totalCostUsd;

    @Property(name = "computed_at")
    public ZonedDateTime computedAt;

    @Property(name = "by_agent")
    public List<ProjectAgentStatView> byAgent;

    @Property(name = "by_subject")
    public List<ProjectSubjectStatView> bySubject;
}
