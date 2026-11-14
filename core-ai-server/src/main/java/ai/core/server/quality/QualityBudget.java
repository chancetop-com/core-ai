package ai.core.server.quality;

import java.time.Duration;
import java.time.Instant;

/**
 * Hard limits for one quality run. Rules cannot raise them: an administrator changes policy, not the
 * server's cost ceiling. The deadline is wall-clock because it has to survive model calls and retries.
 *
 * @author core-ai
 */
public final class QualityBudget {
    public static final int MAX_PROVIDER_CALLS = 8;
    public static final int MAX_QUESTIONS = 120;
    public static final int MAX_QUESTIONS_PER_CALL = 20;
    public static final int MAX_INPUT_CHARS = 48_000;
    public static final int MAX_STATE_CHARS = 64_000;
    public static final Duration MAX_SINGLE_CALL = Duration.ofSeconds(20);

    public static QualityBudget forDeadline(Duration deadline) {
        var effective = deadline == null || deadline.isZero() || deadline.isNegative()
            ? Duration.ofSeconds(45) : deadline;
        return new QualityBudget(Instant.now().plus(effective));
    }

    public static QualityBudget forTest() {
        return new QualityBudget(Instant.now().plus(Duration.ofSeconds(45)));
    }

    private final Instant deadlineAt;
    private int calls;
    private int questions;
    private int inputChars;

    private QualityBudget(Instant deadlineAt) {
        this.deadlineAt = deadlineAt;
    }

    public Duration remaining() {
        var remaining = Duration.between(Instant.now(), deadlineAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public boolean exhausted() {
        return calls >= MAX_PROVIDER_CALLS || questions >= MAX_QUESTIONS || remaining().isZero();
    }

    public boolean canDecide(int questionCount, int stateChars) {
        if (questionCount <= 0 || questionCount > MAX_QUESTIONS_PER_CALL || stateChars > MAX_STATE_CHARS) {
            return false;
        }
        if (exhausted()) return false;
        if (questions + questionCount > MAX_QUESTIONS || inputChars + stateChars > MAX_INPUT_CHARS) return false;
        return remaining().toMillis() > 500;
    }

    public Duration callTimeout() {
        var remaining = remaining();
        if (remaining.compareTo(MAX_SINGLE_CALL) > 0) return MAX_SINGLE_CALL;
        return remaining;
    }

    public void consume(int questionCount, int stateChars) {
        calls++;
        questions += questionCount;
        inputChars += stateChars;
    }

    public int calls() {
        return calls;
    }

    public int questions() {
        return questions;
    }

    public int inputChars() {
        return inputChars;
    }
}
