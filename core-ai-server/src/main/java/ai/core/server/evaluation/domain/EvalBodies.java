package ai.core.server.evaluation.domain;

import java.util.List;
import java.util.Map;

/**
 * Versioned JSON payloads stored in evaluation tables. These are internal shapes; the
 * externally visible contracts are the WebService DTOs.
 *
 * @author stephen
 */
public final class EvalBodies {
    private EvalBodies() {
    }

    public static class CaseBody {
        public String caseId;
        public CaseInput input;
        public CaseReference reference;
        public List<String> graderRefs;
        public String graderParametersJson;
        public CaseEnvironment environment;
        public CaseMetadata metadata;
    }

    public static class CaseInput {
        public String text;
        public Map<String, String> variables;
    }

    public static class CaseReference {
        public String output;
        public List<String> answers;
        public String rubric;
        public List<EvalAssertion> assertions;
    }

    public static class EvalAssertion {
        public String kind;
        public String path;
        public String jsonPointer;
        public Double expectedNumber;
        public Double tolerance;
        public String expectedString;
        public Boolean expectedBoolean;
    }

    public static class CaseEnvironment {
        public String fixtureRef;
        public String clock;
    }

    public static class CaseMetadata {
        public List<String> tags;
        public String split;
        public Double weight;
        public String source;
        public String note;
    }

    public static class GraderSuiteBody {
        public List<GraderDefinition> graders;
        public String successPolicy;
    }

    public static class GraderDefinition {
        public String id;
        public String kind;
        public Boolean required;
        public String mode;
        public String normalization;
        public String pattern;
        public String contains;
        public Double failMax;
        public Double passMin;
        public String metricName;
    }

    public static class EvalSpecBody {
        public String datasetRevisionId;
        public String datasetFingerprint;
        public String graderSuiteId;
        public String profile;
        public Integer repetitions;
        public Integer agentConcurrency;
        public Integer trialDeadlineSeconds;
        public Integer trialMaxTurns;
        public Integer runDeadlineSeconds;
        public String defaultEvaluationClock;
        public String split;
        public List<String> caseIds;
    }

    public static class CandidateSnapshotBody {
        public String agentId;
        public String source;
        public String sourceRevision;
        public String name;
        public String description;
        public String definitionType;
        public String systemPrompt;
        public String model;
        public String multiModalModel;
        public Double temperature;
        public String thinkingEffort;
        public Integer maxTurns;
        public Integer timeoutSeconds;
        public String inputTemplate;
        public Map<String, String> variables;
        public String responseSchema;
        public Boolean memoryEnabled;
        public Boolean modelVersionPinned;
        public List<String> capabilityNotes;
    }

    public static class GradingBundle {
        public String graderSuiteId;
        public List<GraderResult> results;
    }

    public static class GraderResult {
        public String graderId;
        public Boolean required;
        public String status;
        public String verdict;
        public Double score;
        public String feedback;
        public List<MetricValue> metrics;
        public List<Evidence> evidence;
        public Long durationMs;
        public String error;
    }

    public static class MetricValue {
        public String name;
        public Double value;
        public String unit;
        public String direction;
    }

    public static class Evidence {
        public String kind;
        public String artifactPath;
        public String jsonPointer;
        public String expected;
        public String actual;
    }

    public static class RunReport {
        public Integer plannedTrials;
        public Integer success;
        public Integer failure;
        public Integer unmeasured;
        public Double successLowerBound;
        public Double successUpperBound;
        public Double measurementCoverage;
        public Double measuredSuccessRate;
        public Double weightedSuccessLowerBound;
        public Double weightedSuccessUpperBound;
        public List<CaseReport> cases;
        public List<GraderReport> graders;
        public Long p50DurationMs;
        public Long p95DurationMs;
        public Integer inputTokens;
        public Integer outputTokens;
        public Boolean costKnown;
        public List<String> notes;
    }

    public static class CaseReport {
        public String caseId;
        public String split;
        public Double weight;
        public Integer success;
        public Integer failure;
        public Integer unmeasured;
        public Double successFraction;
        public Boolean anySuccess;
        public Boolean allSuccess;
        public List<TrialSummary> trials;
    }

    public static class TrialSummary {
        public Integer repetitionIndex;
        public String executionResult;
        public String taskResult;
        public Long durationMs;
        public Integer inputTokens;
        public Integer outputTokens;
    }

    public static class TrialUsage {
        public Integer inputTokens;
        public Integer outputTokens;
    }

    public static class GraderReport {
        public String graderId;
        public Boolean required;
        public Integer applicable;
        public Integer completed;
        public Integer pass;
        public Integer fail;
        public Integer indeterminate;
        public Integer error;
        public Integer notRunTargetFailed;
        public Double passRate;
    }

    public static class ComparisonReport {
        public String grade;
        public List<String> differences;
        public Integer pairedCases;
        public Integer candidateWins;
        public Integer ties;
        public Integer baselineWins;
        public Double meanDelta;
        public Double ciLow;
        public Double ciHigh;
        public Integer regressions;
        public Integer newHardFailures;
        public String statisticsVersion;
        public List<CaseDelta> cases;
    }

    public static class CaseDelta {
        public String caseId;
        public Double baselineSuccessFraction;
        public Double candidateSuccessFraction;
        public Double delta;
    }
}
