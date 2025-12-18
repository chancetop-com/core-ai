package ai.core.agent.memory;

import ai.core.agent.slidingwindow.SlidingWindowConfig;
import ai.core.agent.slidingwindow.SlidingWindowService;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.memory.ShortTermMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-turn memory trace test for debugging short-term memory behavior.
 * This test traces the complete flow of sliding window and summarization.
 *
 * @author xander
 */
class MultiTurnMemoryTraceTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(MultiTurnMemoryTraceTest.class);

    private TracingMockLLMProvider llmProvider;
    private ShortTermMemory shortTermMemory;
    private SlidingWindowConfig slidingWindowConfig;
    private List<Message> messages;
    private AtomicInteger globalTurnCounter;

    @BeforeEach
    void setUp() {
        llmProvider = new TracingMockLLMProvider();
        shortTermMemory = new ShortTermMemory(1000, 0.33, Runnable::run);
        shortTermMemory.setLLMProvider(llmProvider, "test-model");
        messages = new ArrayList<>();
        globalTurnCounter = new AtomicInteger(0);
    }

    /**
     * Test to trace multi-turn conversation with sliding window.
     * This will reveal the turn counting bug after sliding.
     */
    @Test
    void traceMultiTurnWithSlidingWindow() {
        LOGGER.info("========== Multi-Turn Memory Trace Test ==========");
        LOGGER.info("Configuration: maxTurns=5, batchSize=max(5, 5*0.67)=5");

        slidingWindowConfig = SlidingWindowConfig.builder()
            .maxTurns(5)
            .triggerThreshold(0.8)
            .targetThreshold(0.6)
            .build();

        var slidingWindow = new SlidingWindowService(slidingWindowConfig, llmProvider, "test-model");
        messages.add(Message.of(RoleType.SYSTEM, "You are a helpful assistant."));
        logState("Initial");

        for (int turn = 1; turn <= 12; turn++) {
            executeTurn(turn, slidingWindow);
        }

        logFinalState();
    }

    private void executeTurn(int turn, SlidingWindowService slidingWindow) {
        LOGGER.info("\n========== TURN {} ==========", turn);
        globalTurnCounter.incrementAndGet();
        messages.add(Message.of(RoleType.USER, "User question " + turn));

        int currentTurnsInList = countUserMessages(messages);
        int batchSize = Math.max(5, (int) (slidingWindowConfig.getMaxTurns() * 0.67));
        logAsyncTriggerCheck(currentTurnsInList, batchSize);
        checkAndTriggerAsync(currentTurnsInList, batchSize);
        checkAndSlide(slidingWindow);

        messages.add(Message.of(RoleType.ASSISTANT, "Assistant answer " + turn));
        logState("After turn " + turn);
    }

    private void logAsyncTriggerCheck(int currentTurnsInList, int batchSize) {
        int summarizedUpTo = shortTermMemory.getSummarizedUpTo();
        int unsummarizedTurns = currentTurnsInList - summarizedUpTo;
        LOGGER.info("Before sliding check:");
        LOGGER.info("  - Global turn: {}", globalTurnCounter.get());
        LOGGER.info("  - USER messages in list: {}", currentTurnsInList);
        LOGGER.info("  - summarizedUpTo: {}", summarizedUpTo);
        LOGGER.info("  - unsummarizedTurns: {} (need >= {} for async)", unsummarizedTurns, batchSize);
    }

    private void checkAndTriggerAsync(int currentTurnsInList, int batchSize) {
        if (shortTermMemory.shouldTriggerBatchAsync(currentTurnsInList, batchSize)) {
            LOGGER.info("  >>> ASYNC TRIGGER CONDITION MET!");
            var msgsToSummarize = getMessagesInTurnRange(messages, shortTermMemory.getSummarizedUpTo(), currentTurnsInList);
            LOGGER.info("  >>> Messages to summarize: {} messages", msgsToSummarize.size());
            logMessages("  >>> ", msgsToSummarize);
            shortTermMemory.triggerBatchAsync(msgsToSummarize, currentTurnsInList);
            shortTermMemory.waitForAsyncCompletion();
        }
    }

    private void checkAndSlide(SlidingWindowService slidingWindow) {
        boolean shouldSlide = slidingWindow.shouldSlide(messages);
        LOGGER.info("  - shouldSlide: {}", shouldSlide);

        if (shouldSlide) {
            LOGGER.info("  >>> SLIDING WINDOW TRIGGERED!");
            var evicted = slidingWindow.getEvictedMessages(messages);
            LOGGER.info("  >>> Evicted messages: {}", evicted.size());
            logMessages("  >>> Evicted: ", evicted);

            if (shortTermMemory.getSummary().isEmpty() && !evicted.isEmpty()) {
                LOGGER.info("  >>> Summary empty, doing sync summarization");
                shortTermMemory.summarize(evicted);
            }

            var beforeCount = messages.size();
            messages = new ArrayList<>(slidingWindow.slide(messages));
            LOGGER.info("  >>> Slid: {} -> {} messages", beforeCount, messages.size());
        }
    }

    private void logFinalState() {
        LOGGER.info("\n========== FINAL STATE ==========");
        LOGGER.info("Summary: {}", shortTermMemory.getSummary());
        LOGGER.info("summarizedUpTo: {}", shortTermMemory.getSummarizedUpTo());
        LOGGER.info("Final messages ({}):", messages.size());
        logMessages("  ", messages);
    }

    /**
     * Test to demonstrate the turn counting bug.
     * After sliding, currentTurns counts from the list, not global turns.
     */
    @Test
    void demonstrateTurnCountingBug() {
        LOGGER.info("========== Turn Counting Bug Demonstration ==========");

        slidingWindowConfig = SlidingWindowConfig.builder()
            .maxTurns(3)
            .build();

        var slidingWindow = new SlidingWindowService(slidingWindowConfig, llmProvider, "test-model");

        messages.add(Message.of(RoleType.SYSTEM, "System prompt"));

        // Add 4 turns
        for (int i = 1; i <= 4; i++) {
            messages.add(Message.of(RoleType.USER, "Q" + i));
            messages.add(Message.of(RoleType.ASSISTANT, "A" + i));
        }

        LOGGER.info("Before sliding:");
        LOGGER.info("  Messages count: {}", messages.size());
        LOGGER.info("  USER count in list: {}", countUserMessages(messages));

        // Manually set summarizedUpTo to 3 (simulating async completed)
        // Using reflection since there's no public setter
        shortTermMemory.triggerBatchAsync(List.of(Message.of(RoleType.USER, "dummy")), 3);
        shortTermMemory.waitForAsyncCompletion();
        LOGGER.info("  Set summarizedUpTo to: {}", shortTermMemory.getSummarizedUpTo());

        // Slide
        messages = new ArrayList<>(slidingWindow.slide(messages));

        LOGGER.info("\nAfter sliding:");
        LOGGER.info("  Messages count: {}", messages.size());
        LOGGER.info("  USER count in list: {}", countUserMessages(messages));
        LOGGER.info("  summarizedUpTo: {}", shortTermMemory.getSummarizedUpTo());

        int currentTurnsInList = countUserMessages(messages);
        int summarizedUpTo = shortTermMemory.getSummarizedUpTo();
        int unsummarizedTurns = currentTurnsInList - summarizedUpTo;

        LOGGER.info("\nBug demonstration:");
        LOGGER.info("  currentTurnsInList = {} (from remaining messages)", currentTurnsInList);
        LOGGER.info("  summarizedUpTo = {} (global turn number)", summarizedUpTo);
        LOGGER.info("  unsummarizedTurns = {} - {} = {}", currentTurnsInList, summarizedUpTo, unsummarizedTurns);
        LOGGER.info("  Problem: currentTurnsInList is LOCAL count, summarizedUpTo is GLOBAL!");

        // Add more turns
        for (int i = 5; i <= 7; i++) {
            messages.add(Message.of(RoleType.USER, "Q" + i));
            messages.add(Message.of(RoleType.ASSISTANT, "A" + i));
        }

        currentTurnsInList = countUserMessages(messages);
        unsummarizedTurns = currentTurnsInList - summarizedUpTo;

        LOGGER.info("\nAfter adding turns 5-7:");
        LOGGER.info("  currentTurnsInList = {}", currentTurnsInList);
        LOGGER.info("  summarizedUpTo = {}", summarizedUpTo);
        LOGGER.info("  unsummarizedTurns = {}", unsummarizedTurns);
        LOGGER.info("  Expected: turns 4-7 unsummarized (4 turns)");
        LOGGER.info("  Actual calculation says: {} unsummarized", unsummarizedTurns);
    }

    /**
     * Test getMessagesInTurnRange behavior after sliding.
     */
    @Test
    void demonstrateGetMessagesInTurnRangeBug() {
        LOGGER.info("========== getMessagesInTurnRange Bug Demonstration ==========");

        messages.add(Message.of(RoleType.SYSTEM, "System"));

        // Simulate post-slide state: messages only contain turns 4-8
        // (turns 1-3 were slid away)
        for (int i = 4; i <= 8; i++) {
            messages.add(Message.of(RoleType.USER, "Question from turn " + i));
            messages.add(Message.of(RoleType.ASSISTANT, "Answer from turn " + i));
        }

        LOGGER.info("Simulated post-slide messages (turns 4-8):");
        logMessages("  ", messages);

        // Try to get turns 5-8 (global turn numbers)
        int fromTurn = 4;  // summarizedUpTo
        int toTurn = 8;    // currentTurns (but this is wrong!)

        LOGGER.info("\nCalling getMessagesInTurnRange(messages, {}, {})", fromTurn, toTurn);
        LOGGER.info("Expected: Messages from global turn 5-8");

        var result = getMessagesInTurnRange(messages, fromTurn, toTurn);

        LOGGER.info("Actual result ({} messages):", result.size());
        logMessages("  ", result);

        LOGGER.info("\nProblem analysis:");
        LOGGER.info("  - getMessagesInTurnRange uses LOCAL turnCount (1, 2, 3, 4, 5)");
        LOGGER.info("  - But fromTurn={} and toTurn={} are GLOBAL turn numbers", fromTurn, toTurn);
        LOGGER.info("  - It will return messages where localTurnCount > {} && <= {}", fromTurn, toTurn);
        LOGGER.info("  - Since we only have 5 local turns, turnCount 5-8 doesn't exist!");
    }

    /**
     * Full integration test showing the complete problem scenario.
     */
    @Test
    void fullScenarioWithBugs() {
        LOGGER.info("========== Full Scenario with All Bugs ==========");

        slidingWindowConfig = SlidingWindowConfig.builder()
            .maxTurns(5)
            .build();

        var slidingWindow = new SlidingWindowService(slidingWindowConfig, llmProvider, "test-model");
        int batchSize = Math.max(5, (int) (5 * 0.67));
        messages.add(Message.of(RoleType.SYSTEM, "System"));

        runPhase1BuildupTurns(batchSize);
        runPhase2TriggerSlide(slidingWindow);
        runPhase3LogStateAfterSliding();
        runPhase4ObserveBug(batchSize);
        logFullScenarioFinalAnalysis();
    }

    private void runPhase1BuildupTurns(int batchSize) {
        LOGGER.info("Phase 1: Build up to trigger async (need {} turns)", batchSize);
        for (int i = 1; i <= 5; i++) {
            messages.add(Message.of(RoleType.USER, "Q" + i));
            messages.add(Message.of(RoleType.ASSISTANT, "A" + i));

            int userCount = countUserMessages(messages);
            int unsummarized = userCount - shortTermMemory.getSummarizedUpTo();
            LOGGER.info("Turn {}: userCount={}, unsummarized={}", i, userCount, unsummarized);

            if (shortTermMemory.shouldTriggerBatchAsync(userCount, batchSize)) {
                LOGGER.info("  >> Async triggered for turns 1-{}", userCount);
                var toSummarize = getMessagesInTurnRange(messages, 0, userCount);
                shortTermMemory.triggerBatchAsync(toSummarize, userCount);
                shortTermMemory.waitForAsyncCompletion();
                LOGGER.info("  >> summarizedUpTo now: {}", shortTermMemory.getSummarizedUpTo());
            }
        }
    }

    private void runPhase2TriggerSlide(SlidingWindowService slidingWindow) {
        LOGGER.info("\nPhase 2: Add turn 6 - should trigger slide");
        messages.add(Message.of(RoleType.USER, "Q6"));
        messages.add(Message.of(RoleType.ASSISTANT, "A6"));

        if (slidingWindow.shouldSlide(messages)) {
            LOGGER.info("Sliding triggered!");
            var evicted = slidingWindow.getEvictedMessages(messages);
            LOGGER.info("Evicted: {} messages", evicted.size());
            messages = new ArrayList<>(slidingWindow.slide(messages));
            LOGGER.info("After slide: {} messages, {} USER", messages.size(), countUserMessages(messages));
        }
    }

    private void runPhase3LogStateAfterSliding() {
        LOGGER.info("\nPhase 3: State after sliding");
        LOGGER.info("  summarizedUpTo = {} (global)", shortTermMemory.getSummarizedUpTo());
        LOGGER.info("  USER in list = {} (local)", countUserMessages(messages));
    }

    private void runPhase4ObserveBug(int batchSize) {
        LOGGER.info("\nPhase 4: Add more turns and observe the bug");
        for (int i = 7; i <= 12; i++) {
            messages.add(Message.of(RoleType.USER, "Q" + i));
            messages.add(Message.of(RoleType.ASSISTANT, "A" + i));
            processPhase4Turn(i, batchSize);
        }
    }

    private void processPhase4Turn(int turn, int batchSize) {
        int userCount = countUserMessages(messages);
        int unsummarized = userCount - shortTermMemory.getSummarizedUpTo();
        LOGGER.info("Turn {}: localUserCount={}, summarizedUpTo={}, unsummarized={}",
            turn, userCount, shortTermMemory.getSummarizedUpTo(), unsummarized);

        boolean shouldTrigger = shortTermMemory.shouldTriggerBatchAsync(userCount, batchSize);
        LOGGER.info("  shouldTriggerAsync: {} (need unsummarized >= {})", shouldTrigger, batchSize);

        if (shouldTrigger) {
            LOGGER.info("  >> Attempting to get messages in range ({}, {})",
                shortTermMemory.getSummarizedUpTo(), userCount);
            var toSummarize = getMessagesInTurnRange(messages, shortTermMemory.getSummarizedUpTo(), userCount);
            LOGGER.info("  >> Got {} messages to summarize", toSummarize.size());

            if (!toSummarize.isEmpty()) {
                shortTermMemory.triggerBatchAsync(toSummarize, userCount);
                shortTermMemory.waitForAsyncCompletion();
            }
        }
    }

    private void logFullScenarioFinalAnalysis() {
        LOGGER.info("\n========== FINAL ANALYSIS ==========");
        LOGGER.info("Summary content: {}", shortTermMemory.getSummary());
        LOGGER.info("Remaining messages:");
        logMessages("  ", messages);
        LOGGER.info("\nMissing information: Turns that were never summarized!");
    }

    // Helper methods

    private int countUserMessages(List<Message> msgs) {
        return (int) msgs.stream().filter(m -> m.role == RoleType.USER).count();
    }

    private void logState(String phase) {
        LOGGER.info("--- {} ---", phase);
        LOGGER.info("  Messages: {}", messages.size());
        LOGGER.info("  USER messages: {}", countUserMessages(messages));
        LOGGER.info("  Summary: '{}'", getSummaryPreview());
    }

    private String getSummaryPreview() {
        String summary = shortTermMemory.getSummary();
        if (summary.isEmpty()) {
            return "(empty)";
        }
        return summary.substring(0, Math.min(50, summary.length())) + "...";
    }

    private void logMessages(String prefix, List<Message> msgs) {
        for (var msg : msgs) {
            if (msg.role == RoleType.SYSTEM) continue;
            String content = msg.content != null ? msg.content : "";
            if (content.length() > 40) content = content.substring(0, 40) + "...";
            LOGGER.info("{}{}: {}", prefix, msg.role, content);
        }
    }

    /**
     * Replica of AgentMemoryCoordinator.getMessagesInTurnRange for testing.
     */
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

    /**
     * Mock LLM provider that traces all calls.
     */
    static class TracingMockLLMProvider extends LLMProvider {
        private int callCount = 0;

        TracingMockLLMProvider() {
            super(new LLMProviderConfig("test-model", 0.7, null));
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            callCount++;

            boolean isSummarization = request.messages.stream()
                .anyMatch(m -> m.content != null && m.content.contains("[New Conversation]"));

            var response = new CompletionResponse();
            var choice = new Choice();

            if (isSummarization) {
                LOGGER.info("  [LLM] Summarization request #{}", callCount);
                choice.message = Message.of(RoleType.ASSISTANT,
                    "Summary of conversation (call #" + callCount + ")");
            } else {
                choice.message = Message.of(RoleType.ASSISTANT, "Response #" + callCount);
            }

            choice.finishReason = FinishReason.STOP;
            response.choices = List.of(choice);
            response.usage = new Usage();
            response.usage.setPromptTokens(10);
            response.usage.setCompletionTokens(20);
            response.usage.setTotalTokens(30);
            return response;
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            return doCompletion(request);
        }

        @Override
        public EmbeddingResponse embeddings(EmbeddingRequest request) {
            return null;
        }

        @Override
        public RerankingResponse rerankings(RerankingRequest request) {
            return null;
        }

        @Override
        public CaptionImageResponse captionImage(CaptionImageRequest request) {
            return null;
        }

        @Override
        public String name() {
            return "tracing-mock";
        }

        @Override
        public int maxTokens(String model) {
            return 8000;
        }

        @Override
        public int maxTokens() {
            return 8000;
        }
    }
}