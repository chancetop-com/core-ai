package ai.core.decision;

import java.time.Duration;

/**
 * Per-call transport limits. A quality gate must bound the whole publish wait, and a future that is
 * abandoned while the HTTP call keeps running is not a deadline — the timeout has to reach the transport.
 *
 * @author core-ai
 */
public record DecisionOptions(Duration timeout, Duration connectTimeout) {
    public DecisionOptions {
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("decision timeout must be positive");
        }
        if (connectTimeout != null && (connectTimeout.isZero() || connectTimeout.isNegative())) {
            throw new IllegalArgumentException("decision connect timeout must be positive");
        }
    }

    public static DecisionOptions timeout(Duration timeout) {
        return new DecisionOptions(timeout, null);
    }

    public static DecisionOptions none() {
        return new DecisionOptions(null, null);
    }
}
