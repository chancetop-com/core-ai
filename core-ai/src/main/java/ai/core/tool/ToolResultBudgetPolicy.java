package ai.core.tool;

import ai.core.agent.ExecutionContext;
import ai.core.document.Tokenizer;

/**
 * Budget policy for large tool results: persist them and return a preview instead
 * of sending the full payload into the next prompt.
 *
 * @author stephen
 */
public final class ToolResultBudgetPolicy {
    public static ToolCallResult autoSaveIfNeeded(ToolCallResult result, ToolCall tool, ExecutionContext context,
                                                  SaveOperation saveOperation) {
        var budget = context.getRunBudget();
        if (budget == null || budget.tokens() == null || budget.toolResultMaxTokens() <= 0) return result;
        var content = result.getResult();
        if (content == null || content.isBlank()) return result;
        long estimatedTokens = Tokenizer.tokenCount(content);
        if (estimatedTokens <= budget.toolResultMaxTokens()) return result;
        var path = "/workspace/.core-ai/tool-results/" + tool.getName() + "-" + System.currentTimeMillis() + ".json";
        var saved = saveOperation.save(path, result, tool.getName());
        saved.withStats("budget.tool.auto_saved", Boolean.TRUE)
                .withStats("budget.tool.estimated_tokens", estimatedTokens);
        return saved;
    }

    private ToolResultBudgetPolicy() {
    }

    @FunctionalInterface
    public interface SaveOperation {
        ToolCallResult save(String path, ToolCallResult result, String toolName);
    }
}
