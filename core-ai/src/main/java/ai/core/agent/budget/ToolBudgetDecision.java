package ai.core.agent.budget;

/**
 * Admission result for a tool call.
 *
 * @author stephen
 */
public record ToolBudgetDecision(boolean allowed, String reason, long effectiveTimeoutMs) {
    public static ToolBudgetDecision allow(long effectiveTimeoutMs) {
        return new ToolBudgetDecision(true, null, effectiveTimeoutMs);
    }

    public static ToolBudgetDecision reject(String reason) {
        return new ToolBudgetDecision(false, reason, 0);
    }
}
