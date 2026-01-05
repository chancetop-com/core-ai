package ai.core.memory;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author xander
 */
public class ShortTermMemoryLifecycle extends AbstractLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShortTermMemoryLifecycle.class);

    private final ShortTermMemory shortTermMemory;

    public ShortTermMemoryLifecycle(ShortTermMemory shortTermMemory) {
        this.shortTermMemory = shortTermMemory;
    }

    @Override
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    public void beforeModel(CompletionRequest request, ExecutionContext executionContext) {
        if (request == null || request.messages == null) {
            return;
        }

        List<Message> compressed = shortTermMemory.compress(request.messages);
        if (compressed != request.messages) {
            request.messages = compressed;
            LOGGER.debug("Messages compressed before model call");
        }
    }

    public ShortTermMemory getShortTermMemory() {
        return shortTermMemory;
    }
}
