package ai.core.agent.budget;

/**
 * What one tool call is expected to cost. The timeout is the call's requested
 * timeout; the token/cost estimates only matter when those budget dimensions
 * are enabled.
 *
 * @author stephen
 */
public record BudgetProfile(BudgetClass budgetClass,
                            long requestedTimeoutMs,
                            long estimatedOutputTokens,
                            double estimatedCostUsd) {
    public static BudgetProfile cheap(long timeoutMs) {
        return new BudgetProfile(BudgetClass.CHEAP, timeoutMs, 0, 0);
    }

    public static BudgetProfile normal(long timeoutMs) {
        return new BudgetProfile(BudgetClass.NORMAL, timeoutMs, 0, 0);
    }

    public static BudgetProfile expensive(long timeoutMs) {
        return new BudgetProfile(BudgetClass.EXPENSIVE, timeoutMs, 0, 0);
    }

    public static BudgetProfile media(long timeoutMs, double estimatedCostUsd) {
        return new BudgetProfile(BudgetClass.EXPENSIVE, timeoutMs, 0, estimatedCostUsd);
    }

    public BudgetProfile withEstimatedOutputTokens(long tokens) {
        return new BudgetProfile(budgetClass, requestedTimeoutMs, tokens, estimatedCostUsd);
    }
}
