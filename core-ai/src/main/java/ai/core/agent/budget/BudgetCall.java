package ai.core.agent.budget;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Admission handle for one model call. The reservation is held until
 * {@link #markSettled()} succeeds, so an exception after settlement cannot
 * release the same reservation twice.
 *
 * @author stephen
 */
public final class BudgetCall {
    public static BudgetCall reject(String reason) {
        return new BudgetCall(false, reason, null, 0, 0);
    }

    public static BudgetCall accept(BudgetLedger.Reservation reservation, long estimatedPromptTokens,
                                    int maxOutputTokens) {
        return new BudgetCall(true, null, reservation, estimatedPromptTokens, maxOutputTokens);
    }

    private final boolean allowed;
    private final String reason;
    private final BudgetLedger.Reservation reservation;
    private final long estimatedPromptTokens;
    private final int maxOutputTokens;
    private final AtomicBoolean settled = new AtomicBoolean();

    private BudgetCall(boolean allowed, String reason, BudgetLedger.Reservation reservation,
                       long estimatedPromptTokens, int maxOutputTokens) {
        this.allowed = allowed;
        this.reason = reason;
        this.reservation = reservation;
        this.estimatedPromptTokens = estimatedPromptTokens;
        this.maxOutputTokens = maxOutputTokens;
    }

    public boolean allowed() {
        return allowed;
    }

    public String reason() {
        return reason;
    }

    public BudgetLedger.Reservation reservation() {
        return reservation;
    }

    public long estimatedPromptTokens() {
        return estimatedPromptTokens;
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    public boolean settled() {
        return settled.get();
    }

    public boolean markSettled() {
        return settled.compareAndSet(false, true);
    }
}
