package ai.core.api.server.evaluation.response;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class RunReportView {
    @Property(name = "planned_trials")
    public Integer plannedTrials;

    @Property(name = "success")
    public Integer success;

    @Property(name = "failure")
    public Integer failure;

    @Property(name = "unmeasured")
    public Integer unmeasured;

    @Property(name = "success_lower_bound")
    public Double successLowerBound;

    @Property(name = "success_upper_bound")
    public Double successUpperBound;

    @Property(name = "measurement_coverage")
    public Double measurementCoverage;

    @Property(name = "measured_success_rate")
    public Double measuredSuccessRate;

    @Property(name = "weighted_success_lower_bound")
    public Double weightedSuccessLowerBound;

    @Property(name = "weighted_success_upper_bound")
    public Double weightedSuccessUpperBound;

    @Property(name = "p50_duration_ms")
    public Long p50DurationMs;

    @Property(name = "p95_duration_ms")
    public Long p95DurationMs;

    @Property(name = "input_tokens")
    public Integer inputTokens;

    @Property(name = "output_tokens")
    public Integer outputTokens;

    @Property(name = "cost_known")
    public Boolean costKnown;

    @Property(name = "notes")
    public List<String> notes;

    @Property(name = "cases")
    public List<CaseReportView> cases;

    @Property(name = "graders")
    public List<GraderReportView> graders;
}
