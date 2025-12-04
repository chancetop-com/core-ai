package ai.core.memory;

import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for ShortTermMemory.
 *
 * @author stephen
 */
class ShortTermMemoryTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShortTermMemoryTest.class);
    private ShortTermMemory memory;

    @BeforeEach
    void setUp() {
        memory = new ShortTermMemory(5, 1000, true);
    }

    @Test
    void testAddMessage() {
        Message msg = Message.of(RoleType.USER, "Hello");
        memory.add(msg);

        assertEquals(1, memory.size());
        assertEquals("Hello", memory.getMessages().getFirst().content);
        LOGGER.info("Add message test passed");
    }

    @Test
    void testAddString() {
        memory.add("Hello world");

        assertEquals(1, memory.size());
        assertEquals(RoleType.USER, memory.getMessages().getFirst().role);
        LOGGER.info("Add string test passed");
    }

    @Test
    void testSlidingWindowEviction() {
        // Add more messages than maxMessages (5)
        for (int i = 1; i <= 7; i++) {
            memory.add(Message.of(RoleType.USER, "Message " + i));
        }

        // Should only have 5 messages
        assertEquals(5, memory.size());

        // First messages should be evicted
        var messages = memory.getMessages();
        assertFalse(messages.stream().anyMatch(m -> m.content.equals("Message 1")));
        assertFalse(messages.stream().anyMatch(m -> m.content.equals("Message 2")));
        assertTrue(messages.stream().anyMatch(m -> m.content.equals("Message 7")));

        LOGGER.info("Sliding window eviction test passed, remaining: {}", memory.size());
    }

    @Test
    void testEvictedContentTracking() {
        for (int i = 1; i <= 7; i++) {
            memory.add(Message.of(RoleType.USER, "Message " + i));
        }

        assertTrue(memory.hasEvictedContent());
        String evicted = memory.getEvictedContent();
        assertTrue(evicted.contains("Message 1"));
        assertTrue(evicted.contains("Message 2"));

        LOGGER.info("Evicted content: {}", evicted);
    }

    @Test
    void testRollingSummary() {
        memory.setRollingSummary("Previous context summary");
        assertEquals("Previous context summary", memory.getRollingSummary());

        memory.appendRollingSummary("Additional info");
        assertTrue(memory.getRollingSummary().contains("Previous context summary"));
        assertTrue(memory.getRollingSummary().contains("Additional info"));

        LOGGER.info("Rolling summary test passed");
    }

    @Test
    void testBuildContext() {
        memory.setRollingSummary("User prefers dark mode");
        memory.add(Message.of(RoleType.USER, "Hello"));
        memory.add(Message.of(RoleType.ASSISTANT, "Hi there!"));

        String context = memory.buildContext();
        assertTrue(context.contains("Previous Context"));
        assertTrue(context.contains("User prefers dark mode"));
        assertTrue(context.contains("Recent Conversation"));
        assertTrue(context.contains("User: Hello"));
        assertTrue(context.contains("Assistant: Hi there!"));

        LOGGER.info("Build context test passed:\n{}", context);
    }

    @Test
    void testGetLatestExchange() {
        memory.add(Message.of(RoleType.USER, "First question"));
        memory.add(Message.of(RoleType.ASSISTANT, "First answer"));
        memory.add(Message.of(RoleType.USER, "Second question"));
        memory.add(Message.of(RoleType.ASSISTANT, "Second answer"));

        String exchange = memory.getLatestExchange();
        assertTrue(exchange.contains("Second question"));
        assertTrue(exchange.contains("Second answer"));
        assertFalse(exchange.contains("First question"));

        LOGGER.info("Latest exchange:\n{}", exchange);
    }

    @Test
    void testGetRecentMessages() {
        for (int i = 1; i <= 5; i++) {
            memory.add(Message.of(RoleType.USER, "Message " + i));
        }

        var recent = memory.getRecentMessages(3);
        assertEquals(3, recent.size());
        assertEquals("Message 3", recent.get(0).content);
        assertEquals("Message 5", recent.get(2).content);

        LOGGER.info("Get recent messages test passed");
    }

    @Test
    void testRetrieve() {
        memory.add(Message.of(RoleType.USER, "Hello"));
        memory.add(Message.of(RoleType.ASSISTANT, "Hi"));
        memory.add(Message.of(RoleType.USER, "How are you?"));

        var results = memory.retrieve("query", 2);
        assertEquals(2, results.size());
        // Returns most recent messages
        assertTrue(results.get(0).contains("Hi"));
        assertTrue(results.get(1).contains("How are you"));

        LOGGER.info("Retrieve test passed");
    }

    @Test
    void testClear() {
        memory.add(Message.of(RoleType.USER, "Test"));
        memory.setRollingSummary("Summary");

        memory.clear();

        assertEquals(0, memory.size());
        assertTrue(memory.getRollingSummary().isEmpty());
        assertTrue(memory.isEmpty());

        LOGGER.info("Clear test passed");
    }

    @Test
    void testSystemMessagesExcludedFromContext() {
        memory.add(Message.of(RoleType.SYSTEM, "System prompt"));
        memory.add(Message.of(RoleType.USER, "User message"));

        String context = memory.buildContext();
        assertFalse(context.contains("System prompt"));
        assertTrue(context.contains("User message"));

        LOGGER.info("System messages excluded test passed");
    }

    @Test
    void testDefaultConstructor() {
        ShortTermMemory defaultMemory = new ShortTermMemory();
        assertEquals(20, defaultMemory.getMaxMessages());
        assertEquals(4000, defaultMemory.getMaxTokens());

        LOGGER.info("Default constructor test passed");
    }

    @Test
    void testModelBasedMaxTokens() {
        // Using gpt-4o which has 128000 context window, ratio 0.8
        ShortTermMemory modelMemory = new ShortTermMemory(20, "gpt-4o");
        assertEquals(102400, modelMemory.getMaxTokens()); // 128000 * 0.8

        LOGGER.info("Model-based max tokens test passed, maxTokens: {}", modelMemory.getMaxTokens());
    }

    @Test
    void testIsEmpty() {
        assertTrue(memory.isEmpty());
        memory.add(Message.of(RoleType.USER, "Test"));
        assertFalse(memory.isEmpty());

        LOGGER.info("Is empty test passed");
    }

    @Test
    void testMemoryStoreInterface() {
        // Verify it implements MemoryStore correctly
        MemoryStore store = memory;
        store.add("Test content");
        assertEquals(1, store.size());
        assertFalse(store.isEmpty());

        String context = store.buildContext();
        assertTrue(context.contains("Test content"));

        store.clear();
        assertTrue(store.isEmpty());

        LOGGER.info("MemoryStore interface test passed");
    }
}
