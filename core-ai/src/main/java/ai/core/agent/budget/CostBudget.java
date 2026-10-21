package ai.core.agent.budget;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The cost dimension of a run budget. Amounts are stored as micro-USD so that
 * reservation and settlement use exact integer comparisons.
 *
 * @author stephen
 */
public final class CostBudget {
    public static final long MICROS_PER_USD = 1_000_000L;
    private static final long DEFAULT_MIN_RESERVE_MICROS = 20_000;
    private static final long DEFAULT_MAX_RESERVE_MICROS = 500_000;
    private static final double DEFAULT_RESERVE_RATIO = 0.15;

    public static CostBudget ofUsd(double limitUsd, Double reserveUsd) {
        long limit = Math.max(0, Math.round(limitUsd * MICROS_PER_USD));
        long reserve = reserveUsd != null
                ? Math.max(0, Math.round(reserveUsd * MICROS_PER_USD))
                : defaultReserveMicros(limit);
        return new CostBudget(limit, reserve);
    }

    public static long toMicros(double usd) {
        return Math.max(0, Math.round(usd * MICROS_PER_USD));
    }

    private static long defaultReserveMicros(long limitMicros) {
        if (limitMicros <= 0) return 0;
        long reserve = Math.round(limitMicros * DEFAULT_RESERVE_RATIO);
        return Math.min(limitMicros,
                Math.max(DEFAULT_MIN_RESERVE_MICROS, Math.min(reserve, DEFAULT_MAX_RESERVE_MICROS)));
    }

    private final long limitMicros;
    private final long reserveMicros;
    private final AtomicLong spentMicros = new AtomicLong();
    private final AtomicLong mediaSpentMicros = new AtomicLong();

    public CostBudget(long limitMicros, long reserveMicros) {
        this.limitMicros = Math.max(0, limitMicros);
        this.reserveMicros = Math.max(0, Math.min(reserveMicros, this.limitMicros));
    }

    public long limitMicros() {
        return limitMicros;
    }

    public long reserveMicros() {
        return reserveMicros;
    }

    public long spentMicros() {
        return spentMicros.get();
    }

    public long mediaSpentMicros() {
        return mediaSpentMicros.get();
    }

    public long remainingMicros() {
        return Math.max(0, limitMicros - spentMicros.get());
    }

    public long softRemainingMicros() {
        return Math.max(0, remainingMicros() - reserveMicros);
    }

    public BudgetLevel level() {
        if (limitMicros <= 0) return BudgetLevel.NORMAL;
        long remaining = remainingMicros();
        if (remaining <= 0) return BudgetLevel.EXPIRED;
        if (remaining <= reserveMicros) return BudgetLevel.FINAL;
        if (remaining > limitMicros / 2) return BudgetLevel.NORMAL;
        if (remaining > limitMicros / 4) return BudgetLevel.CONSERVE;
        return BudgetLevel.URGENT;
    }

    public void recordMicros(long micros) {
        if (micros > 0) spentMicros.addAndGet(micros);
    }

    public void recordMediaMicros(long micros) {
        if (micros > 0) {
            spentMicros.addAndGet(micros);
            mediaSpentMicros.addAndGet(micros);
        }
    }
}
