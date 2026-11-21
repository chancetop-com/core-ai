package ai.core.server.evaluation.service;

import ai.core.server.evaluation.domain.EvalBodies;
import ai.core.server.evaluation.domain.EvalEnums;
import ai.core.server.evaluation.domain.EvalRunEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Paired case-level comparison. The run loader supplies the frozen specs and reports; this class only
 * decides compatibility and computes statistics.
 *
 * @author stephen
 */
public class EvalComparator {
    private static final String STATISTICS_VERSION = "paired-case-bootstrap/v1";
    private static final int BOOTSTRAP_ITERATIONS = 2000;
    private static final long BOOTSTRAP_SEED = 42L;
    private static final int CI_LOW_INDEX = 50;
    private static final int CI_HIGH_INDEX = 1950;

    public EvalBodies.ComparisonReport compare(ComparisonInput input) {
        var differences = differences(input.baselineSpec(), input.candidateSpec());
        if (!input.baselinePinned() || !input.candidatePinned()) {
            differences.add("model version is not pinned by the provider; comparison is exploratory");
        }
        var report = new EvalBodies.ComparisonReport();
        report.differences = differences;
        report.grade = grade(input, differences);
        if (EvalEnums.ComparisonGrade.INCOMPATIBLE.name().equals(report.grade)) return report;
        report.statisticsVersion = STATISTICS_VERSION;
        var stats = collect(input.baselineReport(), input.candidateReport());
        report.pairedCases = stats.deltas.size();
        report.candidateWins = stats.wins;
        report.ties = stats.ties;
        report.baselineWins = stats.losses;
        report.meanDelta = weightedMean(stats.pairedDeltas, stats.pairedWeights);
        report.regressions = stats.regressions;
        report.newHardFailures = stats.newHardFailures;
        report.cases = stats.deltas;
        var limits = bootstrap(stats.pairedDeltas, stats.pairedWeights);
        report.ciLow = limits[0];
        report.ciHigh = limits[1];
        return report;
    }

    private String grade(ComparisonInput input, List<String> differences) {
        if (!sameDataset(input.baselineSpec(), input.candidateSpec())
            || !input.baselineSpec().graderSuiteId.equals(input.candidateSpec().graderSuiteId)) {
            return EvalEnums.ComparisonGrade.INCOMPATIBLE.name();
        }
        if (!differences.isEmpty()) return EvalEnums.ComparisonGrade.EXPLORATORY.name();
        if (input.baseline().status != EvalEnums.RunStatus.COMPLETED
            || input.candidate().status != EvalEnums.RunStatus.COMPLETED) {
            return EvalEnums.ComparisonGrade.EXPLORATORY.name();
        }
        return EvalEnums.ComparisonGrade.STRICT.name();
    }

    private DeltaStats collect(EvalBodies.RunReport baselineReport, EvalBodies.RunReport candidateReport) {
        var stats = new DeltaStats();
        var baselineCases = byCase(baselineReport);
        var candidateCases = byCase(candidateReport);
        for (var entry : baselineCases.entrySet()) {
            var candidateCase = candidateCases.get(entry.getKey());
            if (candidateCase == null) continue;
            var delta = new EvalBodies.CaseDelta();
            delta.caseId = entry.getKey();
            delta.baselineSuccessFraction = fraction(entry.getValue());
            delta.candidateSuccessFraction = fraction(candidateCase);
            if (delta.baselineSuccessFraction == null || delta.candidateSuccessFraction == null) continue;
            delta.delta = delta.candidateSuccessFraction - delta.baselineSuccessFraction;
            stats.add(delta, entry.getValue().weight == null ? 1.0 : entry.getValue().weight);
        }
        return stats;
    }

    private List<String> differences(EvalBodies.EvalSpecBody baseline, EvalBodies.EvalSpecBody candidate) {
        var differences = new ArrayList<String>();
        if (!sameDataset(baseline, candidate)) differences.add("dataset revision changed");
        if (!baseline.graderSuiteId.equals(candidate.graderSuiteId)) differences.add("grader suite changed");
        if (!baseline.profile.equals(candidate.profile)) differences.add("profile changed");
        if (!baseline.repetitions.equals(candidate.repetitions)) differences.add("repetitions changed");
        if (!baseline.agentConcurrency.equals(candidate.agentConcurrency)) differences.add("agent concurrency changed");
        if (!baseline.trialDeadlineSeconds.equals(candidate.trialDeadlineSeconds)) differences.add("trial deadline changed");
        if (!baseline.trialMaxTurns.equals(candidate.trialMaxTurns)) differences.add("trial max turns changed");
        if (!baseline.defaultEvaluationClock.equals(candidate.defaultEvaluationClock)) differences.add("evaluation clock changed");
        return differences;
    }

    private boolean sameDataset(EvalBodies.EvalSpecBody baseline, EvalBodies.EvalSpecBody candidate) {
        if (baseline.datasetFingerprint != null && candidate.datasetFingerprint != null) {
            return baseline.datasetFingerprint.equals(candidate.datasetFingerprint);
        }
        return baseline.datasetRevisionId.equals(candidate.datasetRevisionId);
    }

    private Map<String, EvalBodies.CaseReport> byCase(EvalBodies.RunReport report) {
        var map = new LinkedHashMap<String, EvalBodies.CaseReport>();
        if (report.cases == null) return map;
        for (var item : report.cases) if (item.caseId != null) map.put(item.caseId, item);
        return map;
    }

    private Double fraction(EvalBodies.CaseReport report) {
        if (report.success == null || report.failure == null) return null;
        var measured = report.success + report.failure;
        return measured == 0 ? null : (double) report.success / measured;
    }

    private Double weightedMean(List<Double> values, List<Double> weights) {
        double weightSum = 0;
        double valueSum = 0;
        var weightIterator = weights.iterator();
        for (var value : values) {
            var weight = weightIterator.next();
            weightSum += weight;
            valueSum += value * weight;
        }
        return weightSum == 0 ? null : valueSum / weightSum;
    }

    @SuppressWarnings("PMD.ForLoopCanBeForeach")
    private double[] bootstrap(List<Double> values, List<Double> weights) {
        if (values.isEmpty()) return new double[]{0, 0};
        var random = new SplittableRandom(BOOTSTRAP_SEED);
        var means = new double[BOOTSTRAP_ITERATIONS];
        for (var i = 0; i < BOOTSTRAP_ITERATIONS; i++) {
            double weightSum = 0;
            double valueSum = 0;
            for (var j = 0; j < values.size(); j++) {
                var index = random.nextInt(values.size());
                weightSum += weights.get(index);
                valueSum += values.get(index) * weights.get(index);
            }
            means[i] = weightSum == 0 ? 0 : valueSum / weightSum;
        }
        java.util.Arrays.sort(means);
        return new double[]{means[CI_LOW_INDEX], means[CI_HIGH_INDEX]};
    }

    public record ComparisonInput(EvalRunEntity baseline, EvalRunEntity candidate,
                                  EvalBodies.EvalSpecBody baselineSpec, EvalBodies.EvalSpecBody candidateSpec,
                                  EvalBodies.RunReport baselineReport, EvalBodies.RunReport candidateReport,
                                  boolean baselinePinned, boolean candidatePinned) {
    }

    private static final class DeltaStats {
        private final List<EvalBodies.CaseDelta> deltas = new ArrayList<>();
        private final List<Double> pairedWeights = new ArrayList<>();
        private final List<Double> pairedDeltas = new ArrayList<>();
        private int wins;
        private int ties;
        private int losses;
        private int regressions;
        private int newHardFailures;

        void add(EvalBodies.CaseDelta delta, double weight) {
            deltas.add(delta);
            pairedWeights.add(weight);
            pairedDeltas.add(delta.delta);
            if (delta.delta > 1e-9) wins++;
            else if (delta.delta < -1e-9) losses++;
            else ties++;
            if (delta.baselineSuccessFraction >= 1.0 && delta.candidateSuccessFraction < 1.0) regressions++;
            if (delta.baselineSuccessFraction > 0 && delta.candidateSuccessFraction <= 0) newHardFailures++;
        }
    }
}
