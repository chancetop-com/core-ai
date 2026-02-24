package ai.core.compression;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author stephen
 */
public class ToolCallPruningLifecycle extends AbstractLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolCallPruningLifecycle.class);

    private final ToolCallPruning toolCallPruning;

    public ToolCallPruningLifecycle(ToolCallPruning toolCallPruning) {
        this.toolCallPruning = toolCallPruning;
    }

    @Override
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    public void beforeModel(CompletionRequest request, ExecutionContext executionContext) {
        if (request == null || request.messages == null) {
            return;
        }

        List<Message> pruned = toolCallPruning.prune(request.messages);
        if (pruned != request.messages) {
            request.messages = pruned;
            LOGGER.debug("Messages pruned before model call");
        }
    }
}
