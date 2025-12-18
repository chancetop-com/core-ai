package ai.core.agent.memory;

import ai.core.IntegrationTest;
import ai.core.agent.Agent;
import ai.core.agent.slidingwindow.SlidingWindowConfig;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.RoleType;
import ai.core.memory.ShortTermMemory;
import core.framework.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for Agent with ShortTermMemory.
 *
 * @author xander
 */
@Disabled
class AgentShortTermMemoryTest extends IntegrationTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentShortTermMemoryTest.class);

    @Inject
    LLMProviders llmProviders;

    private LLMProvider llmProvider;

    @Test
    void testAgentWithDefaultShortTermMemory() {
        // ShortTermMemory is enabled by default
        var agent = Agent.builder()
                .llmProvider(llmProviders.getProvider())
                .systemPrompt("You are a helpful assistant.")
                .model("gpt-4.1")
                .build();

        assertNotNull(agent);
        agent.run("Hello");

        // Verify default ShortTermMemory is working
        var messages = agent.getMessages();
        assertFalse(messages.isEmpty());
        LOGGER.info("Agent with default ShortTermMemory created successfully");
    }

    @Test
    void testSummaryInjectedAsToolMessage() {
        var shortTermMemory = new ShortTermMemory();
        shortTermMemory.setSummary("User prefers dark mode and concise responses.");

        var agent = Agent.builder()
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("You are a helpful assistant.")
            .shortTermMemory(shortTermMemory)
            .build();

        agent.run("Hello");

        // Verify that the summary was injected as a TOOL message
        var messages = agent.getMessages();
        assertFalse(messages.isEmpty());

        var memoryToolMessage = messages.stream()
            .filter(m -> m.role == RoleType.TOOL && "memory_recall_0".equals(m.toolCallId))
            .findFirst();
        assertTrue(memoryToolMessage.isPresent(), "Memory should be injected as TOOL message");
        assertTrue(memoryToolMessage.get().content.contains("User prefers dark mode"));

        LOGGER.info("Memory tool message: {}", memoryToolMessage.get().content);
    }

    @Test
    void testSlidingWindowTriggersSummarization() {
        var shortTermMemory = new ShortTermMemory();

        // Configure sliding window to trigger after 2 turns
        var slidingWindowConfig = SlidingWindowConfig.builder()
            .maxTurns(2)
            .build();

        var agent = Agent.builder()
            .llmProvider(llmProviders.getProvider())
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

        // Verify summary is injected as TOOL message (after sliding)
        var memoryToolMessage = agent.getMessages().stream()
            .filter(m -> m.role == RoleType.TOOL && "memory_recall_0".equals(m.toolCallId))
            .findFirst();
        assertTrue(memoryToolMessage.isPresent(), "Memory should be injected as TOOL message after sliding");
        LOGGER.info("Memory tool message after sliding: {}", memoryToolMessage.get().content);

        // Also verify it works in a new conversation
        var newAgent = Agent.builder()
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("You are a helpful assistant.")
            .shortTermMemory(shortTermMemory)
            .build();

        newAgent.run("Hello again");

        var newMemoryToolMessage = newAgent.getMessages().stream()
            .filter(m -> m.role == RoleType.TOOL && "memory_recall_0".equals(m.toolCallId))
            .findFirst();
        assertTrue(newMemoryToolMessage.isPresent(), "New conversation should have memory as TOOL message");

        LOGGER.info("Full integration test passed");
    }

    @Test
    void testEmptySummaryNotInjected() {
        var shortTermMemory = new ShortTermMemory();
        // Summary is empty by default

        var agent = Agent.builder()
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("You are a helpful assistant.")
            .shortTermMemory(shortTermMemory)
            .build();

        agent.run("Hello");

        var messages = agent.getMessages();
        // Empty summary should not inject memory tool message
        var memoryToolMessage = messages.stream()
            .filter(m -> m.role == RoleType.TOOL && "memory_recall_0".equals(m.toolCallId))
            .findFirst();
        assertFalse(memoryToolMessage.isPresent(), "Empty summary should not inject memory tool message");

        LOGGER.info("Empty summary not injected test passed");
    }

    @Test
    void testAgentWithDisabledShortTermMemory() {
        var agent = Agent.builder()
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("You are a helpful assistant.")
            .disableShortTermMemory()
            .build();

        var result = agent.run("Hello");
        assertNotNull(result);
        LOGGER.info("Agent with disabled ShortTermMemory works normally");
    }

    @Test
    void testBatchAsyncSummarization() {
        var shortTermMemory = new ShortTermMemory();

        // Configure sliding window with 10 turns (batch async triggers at 2/3 = 6-7 turns)
        var slidingWindowConfig = SlidingWindowConfig.builder()
            .maxTurns(10)
            .build();

        var agent = Agent.builder()
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("You are a helpful assistant.")
            .shortTermMemory(shortTermMemory)
            .slidingWindowConfig(slidingWindowConfig)
            .build();

        // Run multiple turns - async should trigger around 2/3 capacity (6-7 turns)
        for (int i = 1; i <= 5; i++) {
            agent.run("Question " + i);
            LOGGER.info("Turn {}: summarizedUpTo={}, summary='{}'",
                i, shortTermMemory.getSummarizedUpTo(), shortTermMemory.getSummary());
        }

        // Summary should still be empty - not enough turns for batch async
        assertTrue(shortTermMemory.getSummary().isEmpty(), "Summary should be empty before async threshold");
        LOGGER.info("Batch async summarization test passed - threshold not yet reached");
    }
}
