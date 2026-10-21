package ai.core.session;

import ai.core.agent.Agent;
import ai.core.agent.budget.CostBudget;
import ai.core.api.server.session.TurnCompleteEvent;

/**
 * Builds turn-complete usage deltas, including cancelled turns. Keeping this in
 * one place ensures budget cancels report the same token/cost figures as normal
 * completions.
 *
 * @author stephen
 */
public final class SessionTurnUsage {
    public static TurnCompleteEvent cancelled(Agent agent, String sessionId, String output, Before before) {
        var event = TurnCompleteEvent.cancelled(sessionId, output);
        populate(agent, event, before);
        return event;
    }

    public static void populate(Agent agent, TurnCompleteEvent event, Before before) {
        var usageAfter = agent.getCurrentTokenUsage();
        event.inputTokens = usageAfter.getPromptTokens() - before.input;
        event.outputTokens = usageAfter.getCompletionTokens() - before.output;
        var detailsAfter = usageAfter.getPromptTokensDetails();
        event.cachedTokens = detailsAfter != null ? detailsAfter.cachedTokens - before.cached : null;
        event.costUsd = agent.getCurrentCostUsd() - before.cost;
        var budget = agent.getExecutionContext().getRunBudget();
        if (budget == null) return;
        event.budgetExhausted = budget.isHardLimitReached();
        event.budgetLevel = budget.level().name();
        event.budgetBinding = budget.binding() == null ? null : budget.binding().name();
        event.budgetTokensSpent = budget.tokens() == null ? null : budget.tokens().spentTokens();
        event.budgetCostSpent = budget.cost() == null ? null
                : budget.cost().spentMicros() / (double) CostBudget.MICROS_PER_USD;
        event.budgetCostEstimated = budget.cost() == null ? null : budget.costEstimateMissing();
    }

    private SessionTurnUsage() {
    }

    public record Before(long input, long output, double cost, long cached) {
    }
}
