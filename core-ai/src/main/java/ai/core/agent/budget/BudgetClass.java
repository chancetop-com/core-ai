package ai.core.agent.budget;

/**
 * How a tool behaves under budget pressure. Classification is argument-aware:
 * a read-only shell command is CHEAP even though shell is usually EXPENSIVE.
 *
 * @author stephen
 */
public enum BudgetClass {
    CHEAP,
    NORMAL,
    EXPENSIVE
}
