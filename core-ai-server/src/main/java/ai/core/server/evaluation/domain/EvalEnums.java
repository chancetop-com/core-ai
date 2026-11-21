package ai.core.server.evaluation.domain;

/**
 * State vocabulary for offline agent evaluations.
 *
 * @author stephen
 */
public final class EvalEnums {
    public static <E extends Enum<E>> E parse(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private EvalEnums() {
    }

    public enum RunStatus {
        QUEUED, PREFLIGHT, RUNNING, FINALIZING, COMPLETED, COMPLETED_WITH_ERRORS,
        BUDGET_EXHAUSTED, CANCELLING, CANCELLED, FAILED
    }

    public enum TrialStatus {
        PENDING, LEASED, PREPARING, EXECUTING, EXECUTION_TERMINAL, GRADING, FINAL
    }

    public enum ExecutionResult {
        SUCCEEDED, AGENT_FAILED, TIMEOUT, PLATFORM_ERROR, OUTCOME_UNKNOWN, CANCELLED, NOT_EXECUTED
    }

    public enum TaskResult {
        SUCCESS, FAILURE, UNMEASURED
    }

    public enum Verdict {
        PASS, FAIL, INDETERMINATE, NOT_APPLICABLE
    }

    public enum GradingStatus {
        PENDING, RUNNING, COMPLETED, ERROR, CANCELLED, NOT_RUN_TARGET_FAILED
    }

    public enum Purpose {
        FORMAL, SMOKE, DIAGNOSTIC
    }

    public enum Profile {
        OFFLINE_TEXT
    }

    public enum Split {
        DEV, VALIDATION, HOLDOUT
    }

    public enum Source {
        DRAFT, PUBLISHED
    }

    public enum GraderKind {
        EXACT, CONTAINS, REGEX, JSON_ASSERTION
    }

    public enum GraderMode {
        BOOLEAN, BOUNDED_SCORE
    }

    public enum ComparisonGrade {
        STRICT, EXPLORATORY, INCOMPATIBLE
    }
}
