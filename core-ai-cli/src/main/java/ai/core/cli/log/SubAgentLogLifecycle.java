package ai.core.cli.log;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.tool.ToolCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs sub-agent execution details at DEBUG level — mirroring what the main agent
 * outputs to the console via CliEventListener, but written to the log file instead.
 * <p>
 * Add to forked agents (via {@code AgentFork}) to make sub-agent execution traceable
 * through the debug log.
 */
public final class SubAgentLogLifecycle extends AbstractLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(SubAgentLogLifecycle.class);

    private static final int MAX_ARG_LENGTH = 200;
    private static final int MAX_RESULT_LENGTH = 300;

    private static String truncate(String text, int maxLen) {
        if (text == null) return "(null)";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...(" + text.length() + " chars)";
    }

    private final String agentName;

    public SubAgentLogLifecycle(String agentName) {
        this.agentName = agentName;
    }

    @Override
    public void beforeModel(CompletionRequest request, ExecutionContext ctx) {
        int msgCount = request.messages != null ? request.messages.size() : 0;
        LOGGER.info("[{}] beforeModel: {} messages", agentName, msgCount);
    }

    @Override
    public void afterModel(CompletionRequest request, CompletionResponse response, ExecutionContext ctx) {
        if (response.choices == null || response.choices.isEmpty()) {
            LOGGER.info("[{}] afterModel: no choices", agentName);
            return;
        }
        var choice = response.choices.getFirst();
        long inTokens = response.usage != null ? response.usage.getPromptTokens() : 0;
        long outTokens = response.usage != null ? response.usage.getCompletionTokens() : 0;
        long cachedTokens = 0;
        if (response.usage != null) {
            var details = response.usage.getPromptTokensDetails();
            if (details != null) cachedTokens = details.cachedTokens;
        }
        String text;
        if (choice.message == null || choice.message.content == null || choice.message.content.isBlank()) {
            text = choice.finishReason == FinishReason.TOOL_CALLS ? "(tool_calls)" : "(empty)";
        } else {
            text = truncate(choice.message.content, MAX_RESULT_LENGTH);
        }
        var tokenInfo = cachedTokens > 0
                ? String.format("in=%d out=%d cached=%d", inTokens, outTokens, cachedTokens)
                : String.format("in=%d out=%d", inTokens, outTokens);
        LOGGER.info("[{}] afterModel: finishReason={} tokens({}) text={}",
                agentName, choice.finishReason, tokenInfo, text);
    }

    @Override
    public void beforeTool(FunctionCall functionCall, ExecutionContext ctx) {
        LOGGER.info("[{}] tool start: {} args={}",
                agentName, functionCall.function.name, truncate(functionCall.function.arguments, MAX_ARG_LENGTH));
    }

    @Override
    public void afterTool(FunctionCall functionCall, ExecutionContext ctx, ToolCallResult result) {
        String status = result.isDirectReturn() ? "direct" : result.isFailed() ? "error" : "ok";
        LOGGER.info("[{}] tool result: {} status={} result={}",
                agentName, functionCall.function.name, status, truncate(result.toResultForLLM(), MAX_RESULT_LENGTH));
    }

}
