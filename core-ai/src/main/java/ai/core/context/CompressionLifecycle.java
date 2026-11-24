package ai.core.context;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.ContextWindowErrors;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.tool.ToolCallResult;
import ai.core.utils.MessageTokenCounterUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author xander
 */
public class CompressionLifecycle extends AbstractLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(CompressionLifecycle.class);

    private final Compression compression;
    private final CompressionThrashGuard thrashGuard;

    public CompressionLifecycle(Compression compression) {
        this(compression, new CompressionThrashGuard());
    }

    CompressionLifecycle(Compression compression, CompressionThrashGuard thrashGuard) {
        this.compression = compression;
        this.thrashGuard = thrashGuard;
    }

    @Override
    public void beforeAgentRun(AtomicReference<String> query, ExecutionContext executionContext) {
        thrashGuard.beginRun();
    }

    @Override
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    public void beforeModel(CompletionRequest request, ExecutionContext executionContext) {
        if (request == null || request.messages == null) {
            return;
        }
        thrashGuard.check(compression.getMaxContextTokens());
        var budget = executionContext == null ? null : executionContext.getRunBudget();
        if (budget != null && budget.tokens() != null
                && budget.tokens().remainingTokens() <= budget.tokens().reserveTokens()) {
            LOGGER.debug("Skipping compression: token budget is in its reserve");
            return;
        }

        List<Message> compressed = compression.compress(request.messages);
        if (compressed != request.messages) {
            request.messages.clear();
            request.messages.addAll(compressed);
            thrashGuard.onCompression();
            LOGGER.debug("Messages compressed before model call, agent messages updated in-place");
        }
    }

    @Override
    public boolean onModelError(CompletionRequest request, RuntimeException error, ExecutionContext executionContext) {
        if (request == null || request.messages == null || !ContextWindowErrors.isContextWindowExceeded(error)) {
            return false;
        }

        int observedTokens = MessageTokenCounterUtil.count(request.messages);
        if (observedTokens <= 0) {
            return false;
        }
        int declaredLimit = ContextWindowErrors.declaredLimit(error.getMessage());
        int learnedWindow = declaredLimit > 0 ? declaredLimit : (int) (observedTokens * 0.8);
        compression.learnContextWindow(request.model, learnedWindow);

        int before = request.messages.size();
        List<Message> compressed = compression.forceCompress(request.messages);
        if (compressed == request.messages || compressed.size() >= before) {
            LOGGER.warn("context window exceeded but compression could not shrink the conversation, model={}, tokens={}",
                    request.model, observedTokens);
            return false;
        }
        request.messages.clear();
        request.messages.addAll(compressed);
        thrashGuard.onCompression();
        LOGGER.warn("recovered from a context-window rejection, model={}, tokens={}, learnedWindow={}, messages={}->{}",
                request.model, observedTokens, learnedWindow, before, compressed.size());
        return true;
    }

    @Override
    public void afterTool(FunctionCall functionCall, ExecutionContext executionContext, ToolCallResult toolResult) {
        thrashGuard.onToolCall(functionCall);

        if (toolResult == null || !toolResult.isCompleted()) {
            return;
        }

        String result = toolResult.getResult();
        if (result == null || result.isEmpty()) {
            return;
        }

        String sessionId = executionContext != null ? executionContext.getSessionId() : null;
        var sandbox = executionContext != null ? executionContext.getSandbox() : null;
        String toolName = functionCall.function != null ? functionCall.function.name : "unknown";

        try {
            String compressed = compression.compressToolResult(toolName, result, sessionId, sandbox);
            if (!compressed.equals(result)) {
                toolResult.withResult(compressed);
                LOGGER.debug("Tool result compressed for {}", toolName);
            }
        } catch (RuntimeException e) {
            // compression is best-effort, never fail the turn because of it, keep the original result
            LOGGER.warn("Failed to compress tool result for {}, keeping original", toolName, e);
        }
    }
}
