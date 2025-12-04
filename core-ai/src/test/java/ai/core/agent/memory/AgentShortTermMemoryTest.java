package ai.core.agent.memory;

import ai.core.agent.Agent;
import ai.core.agent.slidingwindow.SlidingWindowConfig;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for Agent with ShortTermMemory.
 *
 * @author xander
 */
class AgentShortTermMemoryTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentShortTermMemoryTest.class);
    private MockLLMProvider llmProvider;

    @BeforeEach
    void setUp() {
        llmProvider = new MockLLMProvider();
    }

    @Test
    void testAgentWithDefaultShortTermMemory() {
        // ShortTermMemory is enabled by default
        var agent = Agent.builder()
            .llmProvider(llmProvider)
            .systemPrompt("You are a helpful assistant.")
            .build();

        assertNotNull(agent);
        agent.run("Hello");

        // Verify default ShortTermMemory is working
        var messages = agent.getMessages();
        assertFalse(messages.isEmpty());
        LOGGER.info("Agent with default ShortTermMemory created successfully");
    }

    @Test
    void testSummaryInjectedIntoSystemMessage() {
        var shortTermMemory = new ShortTermMemory();
        shortTermMemory.setSummary("User prefers dark mode and concise responses.");

        var agent = Agent.builder()
            .llmProvider(llmProvider)
            .systemPrompt("You are a helpful assistant.")
            .shortTermMemory(shortTermMemory)
            .build();

        agent.run("Hello");

        // Verify that the summary was injected - check the first message (system)
        var messages = agent.getMessages();
        assertFalse(messages.isEmpty());

        var systemMessage = messages.stream()
            .filter(m -> m.role == RoleType.SYSTEM)
            .findFirst();
        assertTrue(systemMessage.isPresent());
        assertTrue(systemMessage.get().content.contains("[Conversation Memory]"));
        assertTrue(systemMessage.get().content.contains("User prefers dark mode"));

        LOGGER.info("System message with summary: {}", systemMessage.get().content);
    }

    @Test
    void testSlidingWindowTriggersSummarization() {
        // Use a mock provider that returns summary when summarization is triggered
        var summarizingProvider = new SummarizingMockLLMProvider();
        var shortTermMemory = new ShortTermMemory();

        // Configure sliding window to trigger after 2 turns
        var slidingWindowConfig = SlidingWindowConfig.builder()
            .maxTurns(2)
            .build();

        var agent = Agent.builder()
            .llmProvider(summarizingProvider)
            .systemPrompt("You are a helpful assistant.")
            .shortTermMemory(shortTermMemory)
            .slidingWindowConfig(slidingWindowConfig)
            .build();

        // Turn 1: First query
        agent.run("What is Java?");
        LOGGER.info("After turn 1, messages: {}, summary: '{}'",
            agent.getMessages().size(), shortTermMemory.getSummary());
        assertTrue(shortTermMemory.getSummary().isEmpty(), "Summary should be empty after first turn");

        // Turn 2: Second query
        agent.run("What is Python?");
        LOGGER.info("After turn 2, messages: {}, summary: '{}'",
            agent.getMessages().size(), shortTermMemory.getSummary());

        // Turn 3: Third query - this should trigger sliding window
        // Because maxTurns=2, when we have 3 turns, it should slide and summarize
        agent.run("What is JavaScript?");
        LOGGER.info("After turn 3, messages: {}, summary: '{}'",
            agent.getMessages().size(), shortTermMemory.getSummary());

        // Verify that summary was created from evicted messages
        assertFalse(shortTermMemory.getSummary().isEmpty(), "Summary should be created after sliding window");
        LOGGER.info("Final summary: {}", shortTermMemory.getSummary());

        // Verify summary is injected into CURRENT conversation's system message (after sliding)
        var systemMessage = agent.getMessages().stream()
            .filter(m -> m.role == RoleType.SYSTEM)
            .findFirst();
        assertTrue(systemMessage.isPresent());
        assertTrue(systemMessage.get().content.contains("[Conversation Memory]"),
            "System message should contain conversation memory after sliding");
        LOGGER.info("Current conversation system message: {}", systemMessage.get().content);

        // Also verify it works in a new conversation
        var newAgent = Agent.builder()
            .llmProvider(summarizingProvider)
            .systemPrompt("You are a helpful assistant.")
            .shortTermMemory(shortTermMemory)
            .build();

        newAgent.run("Hello again");

        var newSystemMessage = newAgent.getMessages().stream()
            .filter(m -> m.role == RoleType.SYSTEM)
            .findFirst();
        assertTrue(newSystemMessage.isPresent());
        assertTrue(newSystemMessage.get().content.contains("[Conversation Memory]"),
            "New conversation should also have conversation memory");

        LOGGER.info("Full integration test passed");
    }

    @Test
    void testEmptySummaryNotInjected() {
        var shortTermMemory = new ShortTermMemory();
        // Summary is empty by default

        var agent = Agent.builder()
            .llmProvider(llmProvider)
            .systemPrompt("You are a helpful assistant.")
            .shortTermMemory(shortTermMemory)
            .build();

        agent.run("Hello");

        var messages = agent.getMessages();
        var systemMessage = messages.stream()
            .filter(m -> m.role == RoleType.SYSTEM)
            .findFirst();

        assertTrue(systemMessage.isPresent());
        // Empty summary should not add [Conversation Memory] block
        assertFalse(systemMessage.get().content.contains("[Conversation Memory]"));

        LOGGER.info("Empty summary not injected test passed");
    }

    @Test
    void testAgentWithDisabledShortTermMemory() {
        // Agent with ShortTermMemory explicitly disabled
        var agent = Agent.builder()
            .llmProvider(llmProvider)
            .systemPrompt("You are a helpful assistant.")
            .disableShortTermMemory()
            .build();

        var result = agent.run("Hello");
        assertNotNull(result);

        LOGGER.info("Agent with disabled ShortTermMemory works normally");
    }

    /**
     * Mock LLM provider for testing.
     */
    static class MockLLMProvider extends LLMProvider {
        private int callCount = 0;

        MockLLMProvider() {
            super(new LLMProviderConfig("test-model", 0.7, null));
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            return createMockResponse();
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            return createMockResponse();
        }

        private CompletionResponse createMockResponse() {
            callCount++;
            var response = new CompletionResponse();
            var choice = new Choice();
            choice.message = Message.of(RoleType.ASSISTANT, "This is a mock response " + callCount);
            choice.finishReason = FinishReason.STOP;
            response.choices = List.of(choice);
            response.usage = new Usage();
            response.usage.setPromptTokens(10);
            response.usage.setCompletionTokens(20);
            response.usage.setTotalTokens(30);
            return response;
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
            return "mock";
        }
    }

    /**
     * Mock LLM provider that can handle summarization requests.
     * Detects summarization prompts and returns appropriate summary.
     */
    static class SummarizingMockLLMProvider extends LLMProvider {
        private int callCount = 0;

        SummarizingMockLLMProvider() {
            super(new LLMProviderConfig("test-model", 0.7, null));
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            callCount++;

            // Check if this is a summarization request (looks for "[New Conversation]" in prompt)
            var isSummarizationRequest = request.messages.stream()
                .anyMatch(m -> m.content != null && m.content.contains("[New Conversation]"));

            var response = new CompletionResponse();
            var choice = new Choice();

            if (isSummarizationRequest) {
                // Return a mock summary
                choice.message = Message.of(RoleType.ASSISTANT,
                    "User asked about programming languages including Java and Python.");
            } else {
                // Return normal response
                choice.message = Message.of(RoleType.ASSISTANT, "Mock response " + callCount);
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
            return "summarizing-mock";
        }
    }
}
