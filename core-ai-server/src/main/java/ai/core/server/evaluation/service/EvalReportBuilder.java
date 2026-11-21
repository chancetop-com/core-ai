package ai.core.server.evaluation.service;

import ai.core.server.evaluation.domain.EvalBodies;
import ai.core.server.evaluation.domain.EvalEnums;
import ai.core.server.evaluation.domain.EvalTrialEntity;
import ai.core.utils.JsonUtil;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the dataset report from durable trials. Missing results stay in the denominator.
 *
 * @author stephen
 */
public class EvalReportBuilder {
    public EvalBodies.RunReport build(EvalBodies.EvalSpecBody spec, List<EvalTrialEntity> trials) {
        var state = new BuilderState(spec);
        for (var trial : trials) state.add(trial);
        return state.report();
    }

    private static class BuilderState {
        private final EvalBodies.EvalSpecBody spec;
        private final Map<String, CaseAccumulator> caseMap = new LinkedHashMap<>();
        private final Map<String, GraderAccumulator> graderMap = new LinkedHashMap<>();
        private final List<Long> durations = new ArrayList<>();
        private int success;
        private int failure;
        private int unmeasured;
        private double totalWeight;
        private double successWeight;
        private double measuredFailWeight;
        private int inputTokens;
        private int outputTokens;

        BuilderState(EvalBodies.EvalSpecBody spec) {
            this.spec = spec;
        }

        void add(EvalTrialEntity trial) {
            countTask(trial);
            var repetitions = spec == null || spec.repetitions == null ? 1 : Math.max(spec.repetitions, 1);
            double trialWeight = (trial.weight == null ? 1.0 : trial.weight) / repetitions;
            totalWeight += trialWeight;
            if (trial.taskResult == EvalEnums.TaskResult.SUCCESS) successWeight += trialWeight;
            if (trial.taskResult == EvalEnums.TaskResult.FAILURE) measuredFailWeight += trialWeight;
            caseMap.computeIfAbsent(trial.caseId, key -> new CaseAccumulator(key, trial.weight, trial.split)).add(trial);
            if (trial.startedAt != null && trial.completedAt != null) {
                durations.add(Math.max(0, Duration.between(trial.startedAt, trial.completedAt).toMillis()));
            }
            addUsage(trial);
            addGraders(trial);
        }

        private void countTask(EvalTrialEntity trial) {
            if (trial.taskResult == EvalEnums.TaskResult.SUCCESS) success++;
            else if (trial.taskResult == EvalEnums.TaskResult.FAILURE) failure++;
            else unmeasured++;
        }

        private void addUsage(EvalTrialEntity trial) {
            var usage = parseUsage(trial.usageJson);
            if (usage != null) {
                if (usage.inputTokens != null) inputTokens += usage.inputTokens;
                if (usage.outputTokens != null) outputTokens += usage.outputTokens;
            }
        }

        private void addGraders(EvalTrialEntity trial) {
            if (trial.gradingJson == null || trial.gradingJson.isBlank()) return;
            EvalBodies.GradingBundle bundle;
            try {
                bundle = JsonUtil.fromJson(EvalBodies.GradingBundle.class, trial.gradingJson);
            } catch (RuntimeException e) {
                return;
            }
            if (bundle.results == null) return;
            for (var result : bundle.results) {
                if (result == null || result.graderId == null) continue;
                graderMap.computeIfAbsent(result.graderId, GraderAccumulator::new).add(result);
            }
        }

        EvalBodies.RunReport report() {
            var report = new EvalBodies.RunReport();
            report.plannedTrials = success + failure + unmeasured;
            report.success = success;
            report.failure = failure;
            report.unmeasured = unmeasured;
            report.costKnown = Boolean.FALSE;
            report.notes = new ArrayList<>();
            report.inputTokens = inputTokens;
            report.outputTokens = outputTokens;
            report.successLowerBound = ratio(success, report.plannedTrials);
            report.successUpperBound = ratio(success + unmeasured, report.plannedTrials);
            report.measurementCoverage = ratio(success + failure, report.plannedTrials);
            report.measuredSuccessRate = ratio(success, success + failure);
            report.weightedSuccessLowerBound = totalWeight > 0 ? successWeight / totalWeight : null;
            report.weightedSuccessUpperBound = totalWeight > 0
                ? (successWeight + (totalWeight - successWeight - measuredFailWeight)) / totalWeight : null;
            report.cases = new ArrayList<>(caseMap.size());
            for (var accumulator : caseMap.values()) report.cases.add(accumulator.toReport());
            report.graders = new ArrayList<>(graderMap.size());
            for (var accumulator : graderMap.values()) report.graders.add(accumulator.toReport());
            report.p50DurationMs = percentile(durations, 50);
            report.p95DurationMs = percentile(durations, 95);
            addNotes(report);
            return report;
        }

        private void addNotes(EvalBodies.RunReport report) {
            if (report.unmeasured > 0) {
                report.notes.add("unmeasured trials keep their planned denominator; the success rate is an interval");
            }
            if (spec == null || spec.caseIds == null || report.plannedTrials
                != spec.caseIds.size() * Math.max(spec.repetitions == null ? 1 : spec.repetitions, 1)) {
                report.notes.add("trial count does not match the frozen plan; report is partial");
            }
            report.notes.add("cost is not computed in this phase; token usage is reported where available");
        }

        private EvalBodies.TrialUsage parseUsage(String usageJson) {
            if (usageJson == null || usageJson.isBlank()) return null;
            try {
                return JsonUtil.fromJson(EvalBodies.TrialUsage.class, usageJson);
            } catch (RuntimeException e) {
                return null;
            }
        }

        private Double ratio(Integer numerator, Integer denominator) {
            if (denominator == null || denominator == 0) return null;
            return (double) numerator / denominator;
        }

        private Long percentile(List<Long> values, int percentile) {
            if (values.isEmpty()) return null;
            var sorted = new ArrayList<>(values);
            sorted.sort(Comparator.naturalOrder());
            int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
            return sorted.get(Math.clamp(index, 0, sorted.size() - 1));
        }
    }

    private static class CaseAccumulator {
        private final String caseId;
        private final Double weight;
        private final String split;
        private final List<EvalBodies.TrialSummary> trials = new ArrayList<>();
        private int success;
        private int failure;
        private int unmeasured;

        CaseAccumulator(String caseId, Double weight, String split) {
            this.caseId = caseId;
            this.weight = weight;
            this.split = split;
        }

        void add(EvalTrialEntity trial) {
            if (trial.taskResult == EvalEnums.TaskResult.SUCCESS) success++;
            else if (trial.taskResult == EvalEnums.TaskResult.FAILURE) failure++;
            else unmeasured++;
            var summary = new EvalBodies.TrialSummary();
            summary.repetitionIndex = trial.repetitionIndex;
            summary.executionResult = trial.executionResult == null ? null : trial.executionResult.name();
            summary.taskResult = trial.taskResult == null ? null : trial.taskResult.name();
            summary.durationMs = trial.startedAt != null && trial.completedAt != null
                ? Math.max(0, Duration.between(trial.startedAt, trial.completedAt).toMillis()) : null;
            if (trial.usageJson != null) {
                try {
                    var usage = JsonUtil.fromJson(EvalBodies.TrialUsage.class, trial.usageJson);
                    summary.inputTokens = usage.inputTokens;
                    summary.outputTokens = usage.outputTokens;
                } catch (RuntimeException ignored) {
                    // usage remains absent rather than guessed
                }
            }
            trials.add(summary);
        }

        EvalBodies.CaseReport toReport() {
            var report = new EvalBodies.CaseReport();
            report.caseId = caseId;
            report.split = split;
            report.weight = weight;
            report.success = success;
            report.failure = failure;
            report.unmeasured = unmeasured;
            report.successFraction = success + failure == 0 ? null : (double) success / (success + failure);
            report.anySuccess = success > 0 ? Boolean.TRUE : unmeasured > 0 ? null : Boolean.FALSE;
            report.allSuccess = failure > 0 ? Boolean.FALSE : unmeasured > 0 ? null : Boolean.TRUE;
            report.trials = trials;
            return report;
        }
    }

    private static class GraderAccumulator {
        private final String graderId;
        private Boolean required;
        private int applicable;
        private int completed;
        private int pass;
        private int fail;
        private int indeterminate;
        private int error;
        private int notRunTargetFailed;

        GraderAccumulator(String graderId) {
            this.graderId = graderId;
        }

        void add(EvalBodies.GraderResult result) {
            if (required == null) required = Boolean.TRUE.equals(result.required);
            applicable++;
            if (EvalEnums.GradingStatus.COMPLETED.name().equals(result.status)) {
                completed++;
                if (EvalEnums.Verdict.PASS.name().equals(result.verdict)) pass++;
                else if (EvalEnums.Verdict.FAIL.name().equals(result.verdict)) fail++;
                else if (EvalEnums.Verdict.INDETERMINATE.name().equals(result.verdict)) indeterminate++;
            } else if (EvalEnums.GradingStatus.NOT_RUN_TARGET_FAILED.name().equals(result.status)) {
                notRunTargetFailed++;
            } else {
                error++;
            }
        }

        EvalBodies.GraderReport toReport() {
            var report = new EvalBodies.GraderReport();
            report.graderId = graderId;
            report.required = required;
            report.applicable = applicable;
            report.completed = completed;
            report.pass = pass;
            report.fail = fail;
            report.indeterminate = indeterminate;
            report.error = error;
            report.notRunTargetFailed = notRunTargetFailed;
            report.passRate = pass + fail == 0 ? null : (double) pass / (pass + fail);
            return report;
        }
    }
}
