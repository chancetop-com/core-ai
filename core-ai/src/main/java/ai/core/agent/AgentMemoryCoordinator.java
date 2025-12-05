package ai.core.agent;

import ai.core.agent.slidingwindow.SlidingWindowConfig;
import ai.core.agent.slidingwindow.SlidingWindowService;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.FunctionCall;
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
 * Coordinates memory operations for Agent, including sliding window and short-term memory.
 *
 * @author xander
 */
class AgentMemoryCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentMemoryCoordinator.class);
    static final String MEMORY_TOOL_NAME = "recall_memory";
    static final String MEMORY_TOOL_CALL_ID = "memory_recall_0";

    private final SlidingWindowConfig slidingWindowConfig;
    private final ShortTermMemory shortTermMemory;
    private final LLMProvider llmProvider;
    private final String model;
    private SlidingWindowService slidingWindow;

    AgentMemoryCoordinator(SlidingWindowConfig slidingWindowConfig,
                           ShortTermMemory shortTermMemory,
                           LLMProvider llmProvider,
                           String model) {
        this.slidingWindowConfig = slidingWindowConfig;
        this.shortTermMemory = shortTermMemory;
        this.llmProvider = llmProvider;
        this.model = model;
    }

    public void applySlidingWindowIfNeeded(Supplier<List<Message>> messagesSupplier,
                                           Consumer<List<Message>> messagesUpdater) {
        if (slidingWindowConfig == null) return;
        initSlidingWindowIfNeeded();

        var messages = messagesSupplier.get();
        triggerBatchAsyncIfNeeded(messages);

        if (slidingWindow.shouldSlide(messages)) {
            var beforeSize = messages.size();
            waitForAsyncAndApplySummary(messages);
            var slidMessages = slidingWindow.slide(messages);
            messagesUpdater.accept(slidMessages);
            LOGGER.info("Sliding window applied: {} -> {} messages", beforeSize, slidMessages.size());
            updateSystemMessageWithSummary(messagesSupplier.get());
        }
    }

    private void initSlidingWindowIfNeeded() {
        if (slidingWindow == null) {
            slidingWindow = new SlidingWindowService(slidingWindowConfig, llmProvider, model);
        }
    }

    private void triggerBatchAsyncIfNeeded(List<Message> messages) {
        if (shortTermMemory == null || slidingWindowConfig == null) return;
        Integer maxTurns = slidingWindowConfig.getMaxTurns();
        if (maxTurns == null || maxTurns <= 0) return;

        int batchSize = Math.max(5, (int) (maxTurns * 0.67));
        int currentTurns = (int) messages.stream().filter(m -> m.role == RoleType.USER).count();

        if (shortTermMemory.shouldTriggerBatchAsync(currentTurns, batchSize)) {
            var msgs = getMessagesInTurnRange(messages, shortTermMemory.getSummarizedUpTo(), currentTurns);
            if (!msgs.isEmpty()) {
                shortTermMemory.triggerBatchAsync(msgs, currentTurns);
            }
        }
    }

    private void waitForAsyncAndApplySummary(List<Message> messages) {
        if (shortTermMemory == null) return;
        shortTermMemory.tryApplyAsyncResult();
        shortTermMemory.waitForAsyncCompletion();

        if (shortTermMemory.getSummary().isEmpty()) {
            var evicted = slidingWindow.getEvictedMessages(messages).stream()
                .filter(m -> !isMemoryToolMessage(m))
                .toList();
            if (!evicted.isEmpty()) {
                shortTermMemory.summarize(evicted);
            }
        }
    }

    private boolean isMemoryToolMessage(Message m) {
        if (m.role == RoleType.TOOL && MEMORY_TOOL_CALL_ID.equals(m.toolCallId)) {
            return true;
        }
        return m.role == RoleType.ASSISTANT && m.toolCalls != null
            && m.toolCalls.stream().anyMatch(tc -> MEMORY_TOOL_CALL_ID.equals(tc.id));
    }

    private List<Message> getMessagesInTurnRange(List<Message> messages, int fromTurn, int toTurn) {
        var result = new ArrayList<Message>();
        int turnCount = 0;
        boolean inRange = false;

        for (var msg : messages) {
            if (msg.role == RoleType.SYSTEM) continue;
            if (msg.role == RoleType.USER) {
                turnCount++;
                inRange = turnCount > fromTurn && turnCount <= toTurn;
            }
            if (inRange) result.add(msg);
        }
        return result;
    }

    private void updateSystemMessageWithSummary(List<Message> messages) {
        if (shortTermMemory == null) return;
        var summary = shortTermMemory.getSummary();
        if (summary == null || summary.isBlank()) return;

        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).role == RoleType.TOOL && MEMORY_TOOL_CALL_ID.equals(messages.get(i).toolCallId)) {
                messages.set(i, Message.of(RoleType.TOOL, summary, MEMORY_TOOL_NAME, MEMORY_TOOL_CALL_ID, null, null));
                return;
            }
        }
        insertMemoryToolCallPair(messages, 1, summary);
    }

    public void injectMemoryAsToolCall(List<Message> messages) {
        if (shortTermMemory == null) return;
        var summary = shortTermMemory.getSummary();
        if (summary == null || summary.isBlank()) return;
        insertMemoryToolCallPair(messages, messages.size(), summary);
    }

    void insertMemoryToolCallPair(List<Message> messages, int idx, String summary) {
        var toolCall = FunctionCall.of(MEMORY_TOOL_CALL_ID, "function", MEMORY_TOOL_NAME, "{}");
        messages.add(idx, Message.of(RoleType.ASSISTANT, "", null, null, null, List.of(toolCall)));
        messages.add(idx + 1, Message.of(RoleType.TOOL, summary, MEMORY_TOOL_NAME, MEMORY_TOOL_CALL_ID, null, null));
    }

    public boolean isEnabled() {
        return slidingWindowConfig != null;
    }
}