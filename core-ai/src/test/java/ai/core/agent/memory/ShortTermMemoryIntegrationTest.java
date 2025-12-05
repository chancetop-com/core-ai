package ai.core.agent.memory;

import ai.core.IntegrationTest;
import ai.core.agent.Agent;
import ai.core.agent.slidingwindow.SlidingWindowConfig;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.RoleType;
import ai.core.memory.ShortTermMemory;
import core.framework.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for Agent with ShortTermMemory using real LLM.
 *
 * @author xander
 */
@Disabled
class ShortTermMemoryIntegrationTest extends IntegrationTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShortTermMemoryIntegrationTest.class);

    @Inject
    LLMProviders llmProviders;

    @Test
    void testShortTermMemorySummarization() {
        var shortTermMemory = new ShortTermMemory();

        // Configure sliding window: keep only 2 conversation turns (user-assistant pairs)
        var slidingWindowConfig = SlidingWindowConfig.builder()
            .maxTurns(2)
            .build();

        var agent = Agent.builder()
            .name("MemoryTestAgent")
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("You are a helpful assistant. Keep your responses concise (1-2 sentences).")
            .shortTermMemory(shortTermMemory)
            .slidingWindowConfig(slidingWindowConfig)
            .build();

        // Turn 1: Ask about Java
        LOGGER.info("=== Turn 1: Asking about Java ===");
        var response1 = agent.run("What is Java programming language? Answer briefly.");
        LOGGER.info("Response 1: {}", response1);
        LOGGER.info("Messages count: {}, Summary: '{}'", agent.getMessages().size(), shortTermMemory.getSummary());
        assertTrue(shortTermMemory.getSummary().isEmpty(), "Summary should be empty after first turn");

        // Turn 2: Ask about Python
        LOGGER.info("=== Turn 2: Asking about Python ===");
        var response2 = agent.run("What is Python? Answer briefly.");
        LOGGER.info("Response 2: {}", response2);
        LOGGER.info("Messages count: {}, Summary: '{}'", agent.getMessages().size(), shortTermMemory.getSummary());

        // Turn 3: This should trigger sliding window and summarization
        LOGGER.info("=== Turn 3: Asking about JavaScript (should trigger sliding window) ===");
        var response3 = agent.run("What is JavaScript? Answer briefly.");
        LOGGER.info("Response 3: {}", response3);
        LOGGER.info("Messages count: {}, Summary length: {}", agent.getMessages().size(), shortTermMemory.getSummary().length());

        // Verify summary was created
        assertFalse(shortTermMemory.getSummary().isEmpty(), "Summary should be created after sliding window");
        LOGGER.info("Summary content: {}", shortTermMemory.getSummary());

        // Verify memory is injected as TOOL message
        var memoryToolMessage = agent.getMessages().stream()
            .filter(m -> m.role == RoleType.TOOL && "memory_recall_0".equals(m.toolCallId))
            .findFirst();
        assertTrue(memoryToolMessage.isPresent(), "Memory should be injected as TOOL message");
        LOGGER.info("Memory tool message: {}", memoryToolMessage.get().content);

        // Turn 4: Ask about previous topics to verify memory is working
        LOGGER.info("=== Turn 4: Testing memory recall ===");
        var response4 = agent.run("What programming languages did we discuss earlier?");
        LOGGER.info("Response 4 (should recall Java/Python from memory): {}", response4);

        // Log final token usage
        LOGGER.info("=== Final Stats ===");
        LOGGER.info("Total tokens: {}", agent.getCurrentTokenUsage().getTotalTokens());
        LOGGER.info("Final summary: {}", shortTermMemory.getSummary());
    }

    @Test
    void testMemoryPersistenceAcrossAgents() {
        // Create a ShortTermMemory that will be shared
        var shortTermMemory = new ShortTermMemory();

        // Agent 1: Have a conversation
        LOGGER.info("=== Agent 1: Initial conversation ===");
        var agent1 = Agent.builder()
            .name("Agent1")
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("You are a helpful assistant.")
            .shortTermMemory(shortTermMemory)
            .slidingWindowConfig(SlidingWindowConfig.builder().maxTurns(2).build())
            .build();

        agent1.run("My name is Alice and I like cats.");
        agent1.run("I also enjoy reading science fiction books.");
        agent1.run("What's your favorite color?"); // This should trigger sliding and summarization

        LOGGER.info("After Agent 1, summary: {}", shortTermMemory.getSummary());
        assertFalse(shortTermMemory.getSummary().isEmpty(), "Summary should exist after Agent 1");

        // Agent 2: Use the same memory
        LOGGER.info("=== Agent 2: Using same memory ===");
        var agent2 = Agent.builder()
                .name("Agent2")
                .llmProvider(llmProviders.getProvider())
                .systemPrompt("You are a helpful assistant.")
                .shortTermMemory(shortTermMemory)
                .build();

        // Verify system message has the memory from Agent 1
        var response = agent2.run("What do you know about me?");
        LOGGER.info("Agent 2 response (should know about Alice/cats/sci-fi): {}", response);

        var memoryToolMessage = agent2.getMessages().stream()
            .filter(m -> m.role == RoleType.TOOL && "memory_recall_0".equals(m.toolCallId))
            .findFirst();
        assertTrue(memoryToolMessage.isPresent(), "Agent 2 should have memory from Agent 1 as TOOL message");
        LOGGER.info("Agent 2 memory tool message: {}", memoryToolMessage.get().content);
    }

    /**
     * Test that ShortTermMemory works transparently without user configuration.
     * Users don't need to create ShortTermMemory manually - it's enabled by default.
     */
    @Test
    void testTransparentMemory() {
        // User just builds agent normally - ShortTermMemory is auto-enabled
        var agent = Agent.builder()
            .name("TransparentMemoryAgent")
            .llmProvider(llmProviders.getProvider())
            .systemPrompt("You are a helpful assistant.")
            .slidingWindowTurns(2)  // Simple API: keep 2 turns
            .build();

        // Normal conversation - user doesn't need to think about memory
        LOGGER.info("=== Transparent memory test ===");
        agent.run("My favorite food is pizza.");
        agent.run("I live in New York.");
        agent.run("What's the weather like today?"); // Triggers sliding + auto summarization

        // Memory works automatically - verify by asking recall question
        var response = agent.run("What do you remember about me?");
        LOGGER.info("Agent response (should recall pizza/New York): {}", response);

        // Verify memory is injected as TOOL message
        var memoryToolMessage = agent.getMessages().stream()
            .filter(m -> m.role == RoleType.TOOL && "memory_recall_0".equals(m.toolCallId))
            .findFirst();
        assertTrue(memoryToolMessage.isPresent(), "Memory should be automatically injected as TOOL message");
        LOGGER.info("Memory tool message with auto memory: {}", memoryToolMessage.get().content);
    }
}
