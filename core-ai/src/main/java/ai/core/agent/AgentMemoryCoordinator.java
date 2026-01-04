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
            List<Message> summaryMessages = compressEvictedIfNeeded(messages);

            var slidMessages = slidingWindow.slide(messages);

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

        for (var msg : messages) {
            if (msg.role == RoleType.SYSTEM) {
                result.add(msg);
                break;
            }
        }

        result.addAll(summaryMessages);

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
