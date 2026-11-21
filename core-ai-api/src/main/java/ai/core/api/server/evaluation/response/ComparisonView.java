package ai.core.api.server.evaluation.response;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ComparisonView {
    @Property(name = "grade")
    public String grade;

    @Property(name = "differences")
    public List<String> differences;

    @Property(name = "paired_cases")
    public Integer pairedCases;

    @Property(name = "candidate_wins")
    public Integer candidateWins;

    @Property(name = "ties")
    public Integer ties;

    @Property(name = "baseline_wins")
    public Integer baselineWins;

    @Property(name = "mean_delta")
    public Double meanDelta;

    @Property(name = "ci_low")
    public Double ciLow;

    @Property(name = "ci_high")
    public Double ciHigh;

    @Property(name = "regressions")
    public Integer regressions;

    @Property(name = "new_hard_failures")
    public Integer newHardFailures;

    @Property(name = "statistics_version")
    public String statisticsVersion;

    @Property(name = "cases")
    public List<CaseDeltaView> cases;
}
