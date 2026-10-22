package ai.core.agent.internal;

import ai.core.agent.ExecutionContext;
import ai.core.agent.budget.RunBudget;
import ai.core.agent.budget.TimeBudget;
import ai.core.agent.budget.TokenEstimator;
import ai.core.llm.domain.FunctionCall;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class AgentHelperTest {
    private static ExecutionContext budgetContext() {
        var time = TimeBudget.ofSeconds(900, 135L, 0, Instant.EPOCH, () -> 100_000_000_000L);
        var budget = new RunBudget(time, null, null, new TokenEstimator(1.0), 0);
        return ExecutionContext.builder().runBudget(budget).build();
    }

    @Test
    void toolMessageCarriesLiveBudgetStatus() {
        var call = FunctionCall.of("call-1", "function", "read_file", "{}");
        var message = AgentHelper.buildToolMessage(call, ToolCallResult.completed("ok"), false, budgetContext());
        var text = message.getTextContent();

        assertTrue(text.contains("[run budget @"));
        assertTrue(text.contains("level NORMAL"));
        assertTrue(text.contains("800s left"));
        assertTrue(text.contains("135s reserve"));
    }

    @Test
    void toolMessageReportsReducedTimeout() {
        var call = FunctionCall.of("call-2", "function", "run_bash_command", "{}");
        var result = ToolCallResult.completed("ok")
                .withStats("budget.timeout.requested_ms", 600_000L)
                .withStats("budget.timeout.effective_ms", 180_000L);
        var message = AgentHelper.buildToolMessage(call, result, false, budgetContext());

        assertTrue(message.getTextContent().contains("timeout reduced 600s -> 180s"));
    }
}
