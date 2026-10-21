package ai.core.agent.budget;

/**
 * Severity of a budget dimension. The combined level of a {@link RunBudget} is the
 * most severe level of its configured dimensions.
 *
 * @author stephen
 */
public enum BudgetLevel {
    NORMAL,
    CONSERVE,
    URGENT,
    FINAL,
    EXPIRED;

    public boolean atLeast(BudgetLevel other) {
        return ordinal() >= other.ordinal();
    }

    public static BudgetLevel worst(BudgetLevel left, BudgetLevel right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.atLeast(right) ? left : right;
    }
}
