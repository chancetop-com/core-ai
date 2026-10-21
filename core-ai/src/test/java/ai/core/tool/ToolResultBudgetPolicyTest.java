package ai.core.tool;

import ai.core.agent.ExecutionContext;
import ai.core.agent.budget.RunBudget;
import ai.core.agent.budget.TokenBudget;
import ai.core.agent.budget.TokenEstimator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class ToolResultBudgetPolicyTest {
    @Test
    void largeResultIsPersistedWhenTokenBudgetIsEnabled() {
        var budget = new RunBudget(null, TokenBudget.of(10_000, 0, 0L, null, true),
                null, new TokenEstimator(1.0), 10);
        var context = ExecutionContext.builder().runBudget(budget).build();
        var tool = new ToolCall() {
            @Override
            public ToolCallResult execute(String arguments) {
                return ToolCallResult.completed("");
            }
        };
        tool.setName("big_tool");
        var result = ToolCallResult.completed("x".repeat(2_000));

        var saved = ToolResultBudgetPolicy.autoSaveIfNeeded(result, tool, context,
                (path, original, name) -> ToolCallResult.completed("saved preview"));

        assertEquals("saved preview", saved.getResult());
        assertEquals(Boolean.TRUE, saved.getStats().get("budget.tool.auto_saved"));
        assertTrue(((Number) saved.getStats().get("budget.tool.estimated_tokens")).longValue() > 10);
    }

    @Test
    void smallResultAndNoBudgetStayInline() {
        var tool = new ToolCall() {
            @Override
            public ToolCallResult execute(String arguments) {
                return ToolCallResult.completed("");
            }
        };
        tool.setName("small_tool");
        var result = ToolCallResult.completed("small");

        assertEquals(result, ToolResultBudgetPolicy.autoSaveIfNeeded(result, tool,
                ExecutionContext.empty(), (path, original, name) -> ToolCallResult.completed("saved")));
    }
}
