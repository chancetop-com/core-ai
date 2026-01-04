package ai.core.agent;

import ai.core.agent.slidingwindow.SlidingWindowConfig;
import ai.core.agent.slidingwindow.SlidingWindowService;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Coordinates sliding window operations for Agent.
 *
 * <p>Note: Short-term memory compression is now handled by {@code ShortTermMemoryLifecycle}
 * in the beforeModel lifecycle hook.
 *
 * @author xander
 */
class AgentMemoryCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentMemoryCoordinator.class);

    private final SlidingWindowConfig slidingWindowConfig;
    private final LLMProvider llmProvider;
    private final String model;
    private SlidingWindowService slidingWindow;

    AgentMemoryCoordinator(SlidingWindowConfig slidingWindowConfig,
                           LLMProvider llmProvider,
                           String model) {
        this.slidingWindowConfig = slidingWindowConfig;
        this.llmProvider = llmProvider;
        this.model = model;
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
            var slidMessages = slidingWindow.slide(messages);
            messagesUpdater.accept(slidMessages);
            LOGGER.info("Sliding window applied: {} -> {} messages", beforeSize, slidMessages.size());
        }
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
