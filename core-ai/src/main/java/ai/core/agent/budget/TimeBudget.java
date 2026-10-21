package ai.core.agent.budget;

import ai.core.agent.CancellationToken;

import java.time.Duration;
import java.time.Instant;
import java.util.function.LongSupplier;

/**
 * The time dimension of a run budget. Remaining time is measured with a monotonic
 * clock; {@link Instant} is only used for display and for converting an absolute
 * deadline once at startup.
 *
 * @author stephen
 */
public final class TimeBudget {
    private static final long DEFAULT_MIN_RESERVE_MILLIS = 30_000;
    private static final long DEFAULT_MAX_RESERVE_MILLIS = 180_000;
    private static final double DEFAULT_RESERVE_RATIO = 0.15;

    public static TimeBudget ofSeconds(long totalSeconds, Long reserveSeconds, long startedAtNanos,
                                       Instant startedAt, LongSupplier nanoClock) {
        long totalMillis = Math.max(0, totalSeconds * 1000);
        long reserveMillis = reserveSeconds != null
                ? Math.max(0, reserveSeconds * 1000)
                : defaultReserveMillis(totalMillis);
        return new TimeBudget(totalMillis, reserveMillis, startedAtNanos, startedAt, nanoClock);
    }

    public static TimeBudget ofDeadline(Instant startedAt, Instant deadline, Long reserveSeconds,
                                        long startedAtNanos, LongSupplier nanoClock) {
        long totalMillis = Math.max(0, Duration.between(startedAt, deadline).toMillis());
        long reserveMillis = reserveSeconds != null
                ? Math.max(0, reserveSeconds * 1000)
                : defaultReserveMillis(totalMillis);
        return new TimeBudget(totalMillis, reserveMillis, startedAtNanos, startedAt, nanoClock);
    }

    public static TimeBudget ofMillis(long totalMillis, long reserveMillis, long startedAtNanos,
                                      Instant startedAt, LongSupplier nanoClock) {
        long total = Math.max(0, totalMillis);
        long reserve = reserveMillis < 0 ? defaultReserveMillis(total) : reserveMillis;
        return new TimeBudget(total, reserve, startedAtNanos, startedAt, nanoClock);
    }

    private static long defaultReserveMillis(long totalMillis) {
        if (totalMillis <= 0) return 0;
        if (totalMillis < 60_000) return totalMillis;
        long reserve = Math.round(totalMillis * DEFAULT_RESERVE_RATIO);
        return Math.max(DEFAULT_MIN_RESERVE_MILLIS, Math.min(reserve, DEFAULT_MAX_RESERVE_MILLIS));
    }

    private final long deadlineNanos;
    private final Instant deadline;
    private final Instant startedAt;
    private final long totalMillis;
    private final long reserveMillis;
    private final LongSupplier nanoClock;

    public TimeBudget(long totalMillis, long reserveMillis, long startedAtNanos, Instant startedAt,
                      LongSupplier nanoClock) {
        this.totalMillis = Math.max(0, totalMillis);
        this.reserveMillis = Math.max(0, Math.min(reserveMillis, this.totalMillis));
        this.deadlineNanos = startedAtNanos + this.totalMillis * 1_000_000L;
        this.deadline = startedAt.plusMillis(this.totalMillis);
        this.startedAt = startedAt;
        this.nanoClock = nanoClock;
    }

    public long remainingMillis() {
        return Math.max(0, (deadlineNanos - nanoClock.getAsLong()) / 1_000_000L);
    }

    public long remainingSeconds() {
        return remainingMillis() / 1000;
    }

    public long totalMillis() {
        return totalMillis;
    }

    public long reserveMillis() {
        return reserveMillis;
    }

    public long softRemainingMillis() {
        return Math.max(0, remainingMillis() - reserveMillis);
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant deadline() {
        return deadline;
    }

    public BudgetLevel level() {
        long remaining = remainingMillis();
        if (remaining <= 0) return BudgetLevel.EXPIRED;
        if (remaining <= reserveMillis) return BudgetLevel.FINAL;
        if (remaining > totalMillis / 2) return BudgetLevel.NORMAL;
        if (remaining > totalMillis / 4) return BudgetLevel.CONSERVE;
        return BudgetLevel.URGENT;
    }

    public boolean isExpired() {
        return remainingMillis() <= 0;
    }

    public long clampToolTimeout(long requestedMs) {
        long available = remainingMillis() - reserveMillis;
        if (available <= 0) return 0;
        return Math.max(1, Math.min(requestedMs, available));
    }

    public Runnable arm(CancellationToken token) {
        if (token == null) return () -> {
        };
        return token.cancelAfter(remainingMillis());
    }
}
