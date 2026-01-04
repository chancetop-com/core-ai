package ai.core.agent;

import ai.core.agent.slidingwindow.SlidingWindowConfig;
import ai.core.agent.slidingwindow.SlidingWindowService;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.memory.ShortTermMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Coordinates sliding window operations for Agent.
 *
 * <p>When sliding window triggers, evicted messages are first compressed into a summary
 * using ShortTermMemory, then the summary is prepended to the remaining messages.
 *
 * @author xander
 */
class AgentMemoryCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentMemoryCoordinator.class);

    private final SlidingWindowConfig slidingWindowConfig;
    private final LLMProvider llmProvider;
    private final String model;
    private final ShortTermMemory shortTermMemory;
    private SlidingWindowService slidingWindow;

    AgentMemoryCoordinator(SlidingWindowConfig slidingWindowConfig,
                           LLMProvider llmProvider,
                           String model,
                           ShortTermMemory shortTermMemory) {
        this.slidingWindowConfig = slidingWindowConfig;
        this.llmProvider = llmProvider;
        this.model = model;
        this.shortTermMemory = shortTermMemory;
    }

    public void applySlidingWindowIfNeeded(Supplier<List<Message>> messagesSupplier,
                                           Consumer<List<Message>> messagesUpdater) {
        if (slidingWindowConfig == null) {
            return;
        }
        initSlidingWindowIfNeeded();

        var messages = messagesSupplier.get();
        if (slidingWindow.shouldSlide(messages)) {
            var beforeSize = messages.size();

            // Compress evicted messages before sliding
            List<Message> summaryMessages = compressEvictedIfNeeded(messages);

            // Slide the window
            var slidMessages = slidingWindow.slide(messages);

            // Prepend summary messages after system message
            if (!summaryMessages.isEmpty()) {
                slidMessages = prependSummaryMessages(slidMessages, summaryMessages);
            }

            messagesUpdater.accept(slidMessages);
            LOGGER.info("Sliding window applied: {} -> {} messages", beforeSize, slidMessages.size());
        }
    }

    private List<Message> compressEvictedIfNeeded(List<Message> messages) {
        if (shortTermMemory == null) {
            return List.of();
        }
        var evictedMessages = slidingWindow.getEvictedMessages(messages);
        if (evictedMessages.isEmpty()) {
            return List.of();
        }
        LOGGER.info("Compressing {} evicted messages before sliding", evictedMessages.size());
        return shortTermMemory.compressEvictedMessages(evictedMessages);
    }

    private List<Message> prependSummaryMessages(List<Message> messages, List<Message> summaryMessages) {
        var result = new ArrayList<Message>();

        // Add system message first if present
        for (var msg : messages) {
            if (msg.role == RoleType.SYSTEM) {
                result.add(msg);
                break;
            }
        }

        // Add summary messages
        result.addAll(summaryMessages);

        // Add remaining messages (skip system message)
        for (var msg : messages) {
            if (msg.role != RoleType.SYSTEM) {
                result.add(msg);
            }
        }

        return result;
    }

    private void initSlidingWindowIfNeeded() {
        if (slidingWindow == null) {
            slidingWindow = new SlidingWindowService(slidingWindowConfig, llmProvider, model);
        }
    }

    public boolean isEnabled() {
        return slidingWindowConfig != null;
    }
}
