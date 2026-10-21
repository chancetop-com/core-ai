package ai.core.agent.budget;

import ai.core.llm.domain.Usage;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The token dimension of a run budget. Total tokens are prompt + completion;
 * cached prompt tokens are a subset and are only excluded in billable-token mode.
 *
 * @author stephen
 */
public final class TokenBudget {
    private static final long DEFAULT_MIN_RESERVE_TOKENS = 2_000;
    private static final long DEFAULT_MAX_RESERVE_TOKENS = 16_000;
    private static final double DEFAULT_RESERVE_RATIO = 0.15;
    private static final long DEFAULT_MIN_OUTPUT_RESERVE = 512;
    private static final long DEFAULT_MAX_OUTPUT_RESERVE = 4_000;

    public static TokenBudget of(long limitTokens, long limitOutputTokens, Long reserveTokens,
                                 Long reserveOutputTokens, boolean countCached) {
        long limit = Math.max(0, limitTokens);
        long outputLimit = Math.max(0, limitOutputTokens);
        long reserve = reserveTokens != null
                ? Math.max(0, reserveTokens)
                : defaultReserveTokens(limit);
        long outputReserve = outputLimit <= 0 ? 0 : reserveOutputTokens != null
                ? Math.max(0, reserveOutputTokens)
                : Math.max(DEFAULT_MIN_OUTPUT_RESERVE,
                        Math.min(Math.round(outputLimit * 0.20), DEFAULT_MAX_OUTPUT_RESERVE));
        return new TokenBudget(limit, outputLimit, reserve, outputReserve, countCached);
    }

    private static long defaultReserveTokens(long limitTokens) {
        if (limitTokens <= 0) return 0;
        long reserve = Math.round(limitTokens * DEFAULT_RESERVE_RATIO);
        return Math.min(limitTokens,
                Math.max(DEFAULT_MIN_RESERVE_TOKENS, Math.min(reserve, DEFAULT_MAX_RESERVE_TOKENS)));
    }

    private final long limitTokens;
    private final long limitOutputTokens;
    private final long reserveTokens;
    private final long reserveOutputTokens;
    private final boolean countCached;

    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong cachedTokens = new AtomicLong();
    private final AtomicLong unclassifiedTokens = new AtomicLong();

    public TokenBudget(long limitTokens, long limitOutputTokens, long reserveTokens,
                       long reserveOutputTokens, boolean countCached) {
        this.limitTokens = Math.max(0, limitTokens);
        this.limitOutputTokens = Math.max(0, limitOutputTokens);
        this.reserveTokens = Math.max(0, Math.min(reserveTokens, this.limitTokens));
        this.reserveOutputTokens = Math.max(0, Math.min(reserveOutputTokens, this.limitOutputTokens));
        this.countCached = countCached;
    }

    public long spentTokens() {
        if (countCached) {
            return promptTokens.get() + completionTokens.get() + unclassifiedTokens.get();
        }
        return Math.max(0, promptTokens.get() - cachedTokens.get())
                + completionTokens.get() + unclassifiedTokens.get();
    }

    public long promptTokens() {
        return promptTokens.get();
    }

    public long completionTokens() {
        return completionTokens.get();
    }

    public long cachedTokens() {
        return cachedTokens.get();
    }

    public long limitTokens() {
        return limitTokens;
    }

    public long limitOutputTokens() {
        return limitOutputTokens;
    }

    public long reserveTokens() {
        return reserveTokens;
    }

    public long reserveOutputTokens() {
        return reserveOutputTokens;
    }

    public long remainingTokens() {
        return Math.max(0, limitTokens - spentTokens());
    }

    public long softRemainingTokens() {
        return Math.max(0, remainingTokens() - reserveTokens);
    }

    public long remainingOutputTokens() {
        if (limitOutputTokens <= 0) return Long.MAX_VALUE;
        return Math.max(0, limitOutputTokens - completionTokens.get());
    }

    public BudgetLevel level() {
        if (limitTokens <= 0) return BudgetLevel.NORMAL;
        long remaining = remainingTokens();
        if (remaining <= 0) return BudgetLevel.EXPIRED;
        if (remaining <= reserveTokens) return BudgetLevel.FINAL;
        if (remaining > limitTokens / 2) return BudgetLevel.NORMAL;
        if (remaining > limitTokens / 4) return BudgetLevel.CONSERVE;
        return BudgetLevel.URGENT;
    }

    public void record(Usage usage) {
        if (usage == null) return;
        var prompt = Math.max(0, usage.getPromptTokens());
        var completion = Math.max(0, usage.getCompletionTokens());
        if (prompt == 0 && completion == 0 && usage.getTotalTokens() > 0) {
            unclassifiedTokens.addAndGet(usage.getTotalTokens());
        } else {
            promptTokens.addAndGet(prompt);
            completionTokens.addAndGet(completion);
        }
        var details = usage.getPromptTokensDetails();
        if (details != null) cachedTokens.addAndGet(Math.max(0, details.cachedTokens));
    }
}
