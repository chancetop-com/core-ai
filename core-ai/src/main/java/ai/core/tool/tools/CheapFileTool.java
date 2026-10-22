package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.agent.budget.BudgetProfile;
import ai.core.tool.ToolCall;

/**
 * Base class for fast local file tools that must stay available while the run
 * budget is in its final-answer reserve.
 *
 * @author stephen
 */
public abstract class CheapFileTool extends ToolCall {
    @Override
    public BudgetProfile budgetProfile(String arguments, ExecutionContext context) {
        return BudgetProfile.cheap(getRequestedTimeoutMs(arguments));
    }
}
