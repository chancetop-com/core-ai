package ai.core.memory;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lifecycle for short-term memory compression.
 *
 * <p>Triggers compression in beforeModel when context exceeds threshold.
 * Replaces older messages with a summary to stay within context limits.
 *
 * @author xander
 */
public class ShortTermMemoryLifecycle extends AbstractLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShortTermMemoryLifecycle.class);

    private final ShortTermMemory shortTermMemory;

    public ShortTermMemoryLifecycle(ShortTermMemory shortTermMemory) {
        this.shortTermMemory = shortTermMemory;
    }

    @Override
    public void beforeAgentRun(AtomicReference<String> query, ExecutionContext executionContext) {
        shortTermMemory.clear();
    }

    @Override
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    public void beforeModel(CompletionRequest request, ExecutionContext executionContext) {
        if (request == null || request.messages == null) {
            return;
        }

        List<Message> compressed = shortTermMemory.compress(request.messages);
        if (compressed != request.messages) {  // reference check: compress returns same object if no compression
            request.messages = compressed;
            LOGGER.debug("Messages compressed before model call");
        }
    }

    public ShortTermMemory getShortTermMemory() {
        return shortTermMemory;
    }
}
