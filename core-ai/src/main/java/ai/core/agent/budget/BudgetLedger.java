package ai.core.agent.budget;

import ai.core.llm.domain.Usage;

/**
 * Atomic reservation and settlement for model/tool calls. Reservations are held
 * across concurrent subagents so two calls cannot both spend the same remaining
 * budget.
 *
 * @author stephen
 */
public final class BudgetLedger {
    private final Object lock = new Object();
    private long reservedTokens;
    private long reservedMicros;

    public Reservation reserve(long tokens, long micros, TokenBudget tokenBudget, CostBudget costBudget) {
        synchronized (lock) {
            if (tokenBudget != null && tokenBudget.limitTokens() > 0) {
                long available = tokenBudget.remainingTokens() - reservedTokens;
                if (available < tokens) {
                    return Reservation.rejected("TOKEN_BUDGET_EXHAUSTED: need " + tokens
                            + " tokens, available " + Math.max(0, available));
                }
            }
            if (costBudget != null && costBudget.limitMicros() > 0) {
                long available = costBudget.remainingMicros() - reservedMicros;
                if (available < micros) {
                    return Reservation.rejected("COST_BUDGET_EXHAUSTED: need $"
                            + String.format("%.6f", micros / (double) CostBudget.MICROS_PER_USD)
                            + ", available $"
                            + String.format("%.6f", Math.max(0, available) / (double) CostBudget.MICROS_PER_USD));
                }
            }
            reservedTokens += tokens;
            reservedMicros += micros;
            return Reservation.accept(tokens, micros);
        }
    }

    public void settle(Reservation reservation, Usage usage, long actualMicros,
                       TokenBudget tokenBudget, CostBudget costBudget) {
        if (reservation == null || !reservation.allowed()) return;
        synchronized (lock) {
            reservedTokens = Math.max(0, reservedTokens - reservation.tokens());
            reservedMicros = Math.max(0, reservedMicros - reservation.micros());
        }
        if (tokenBudget != null) tokenBudget.record(usage);
        if (costBudget != null) costBudget.recordMicros(actualMicros);
    }

    public void release(Reservation reservation) {
        if (reservation == null || !reservation.allowed()) return;
        synchronized (lock) {
            reservedTokens = Math.max(0, reservedTokens - reservation.tokens());
            reservedMicros = Math.max(0, reservedMicros - reservation.micros());
        }
    }

    public long reservedTokens() {
        synchronized (lock) {
            return reservedTokens;
        }
    }

    public long reservedMicros() {
        synchronized (lock) {
            return reservedMicros;
        }
    }

    public record Reservation(boolean allowed, String reason, long tokens, long micros) {
        public static Reservation accept(long tokens, long micros) {
            return new Reservation(true, null, tokens, micros);
        }

        public static Reservation rejected(String reason) {
            return new Reservation(false, reason, 0, 0);
        }
    }
}

