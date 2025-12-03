package ai.core.memory;

import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        var config = ShortTermMemoryConfig.builder()
            .maxMessages(5)
            .maxTokens(1000)
            .enableRollingSummary(true)
            .build();
        memory = new ShortTermMemory(config);
    }

    @Test
    void testAddMessage() {
        Message msg = Message.of(RoleType.USER, "Hello");
        memory.addMessage(msg);

        assertEquals(1, memory.getMessageCount());
        assertEquals("Hello", memory.getMessages().getFirst().content);
        LOGGER.info("Add message test passed");
    }

    @Test
    void testSlidingWindowEviction() {
        // Add more messages than maxMessages (5)
        for (int i = 1; i <= 7; i++) {
            memory.addMessage(Message.of(RoleType.USER, "Message " + i));
        }

        // Should only have 5 messages
        assertEquals(5, memory.getMessageCount());

        // First messages should be evicted
        var messages = memory.getMessages();
        assertFalse(messages.stream().anyMatch(m -> m.content.equals("Message 1")));
        assertFalse(messages.stream().anyMatch(m -> m.content.equals("Message 2")));
        assertTrue(messages.stream().anyMatch(m -> m.content.equals("Message 7")));

        LOGGER.info("Sliding window eviction test passed, remaining: {}", memory.getMessageCount());
    }

    @Test
    void testEvictedContentTracking() {
        for (int i = 1; i <= 7; i++) {
            memory.addMessage(Message.of(RoleType.USER, "Message " + i));
        }

        // Should have evicted content
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
        memory.addMessage(Message.of(RoleType.USER, "Hello"));
        memory.addMessage(Message.of(RoleType.ASSISTANT, "Hi there!"));

        String context = memory.buildContext();
        assertTrue(context.contains("Previous Context Summary"));
        assertTrue(context.contains("User prefers dark mode"));
        assertTrue(context.contains("Recent Conversation"));
        assertTrue(context.contains("User: Hello"));
        assertTrue(context.contains("Assistant: Hi there!"));

        LOGGER.info("Build context test passed:\n{}", context);
    }

    @Test
    void testGetLatestExchange() {
        memory.addMessage(Message.of(RoleType.USER, "First question"));
        memory.addMessage(Message.of(RoleType.ASSISTANT, "First answer"));
        memory.addMessage(Message.of(RoleType.USER, "Second question"));
        memory.addMessage(Message.of(RoleType.ASSISTANT, "Second answer"));

        String exchange = memory.getLatestExchange();
        assertTrue(exchange.contains("Second question"));
        assertTrue(exchange.contains("Second answer"));
        assertFalse(exchange.contains("First question"));

        LOGGER.info("Latest exchange:\n{}", exchange);
    }

    @Test
    void testGetRecentMessages() {
        for (int i = 1; i <= 5; i++) {
            memory.addMessage(Message.of(RoleType.USER, "Message " + i));
        }

        var recent = memory.getRecentMessages(3);
        assertEquals(3, recent.size());
        assertEquals("Message 3", recent.get(0).content);
        assertEquals("Message 5", recent.get(2).content);

        LOGGER.info("Get recent messages test passed");
    }

    @Test
    void testWorkingContext() {
        memory.put("user_preference", "dark mode");
        memory.put("language", "en");
        memory.put("count", 42);

        assertEquals("dark mode", memory.get("user_preference"));
        assertEquals("en", memory.get("language"));
        assertEquals(42, memory.get("count", Integer.class));
        assertTrue(memory.has("user_preference"));
        assertFalse(memory.has("nonexistent"));

        memory.remove("language");
        assertNull(memory.get("language"));

        LOGGER.info("Working context test passed");
    }

    @Test
    void testClear() {
        memory.addMessage(Message.of(RoleType.USER, "Test"));
        memory.setRollingSummary("Summary");
        memory.put("key", "value");

        memory.clear();

        assertEquals(0, memory.getMessageCount());
        assertTrue(memory.getRollingSummary().isEmpty());
        assertTrue(memory.getWorkingContext().isEmpty());

        LOGGER.info("Clear test passed");
    }

    @Test
    void testClearMessages() {
        memory.addMessage(Message.of(RoleType.USER, "Test"));
        memory.put("key", "value");

        memory.clearMessages();

        assertEquals(0, memory.getMessageCount());
        // Working context should be preserved
        assertEquals("value", memory.get("key"));

        LOGGER.info("Clear messages test passed");
    }

    @Test
    void testSystemMessagesExcludedFromContext() {
        memory.addMessage(Message.of(RoleType.SYSTEM, "System prompt"));
        memory.addMessage(Message.of(RoleType.USER, "User message"));

        String context = memory.buildContext();
        assertFalse(context.contains("System prompt"));
        assertTrue(context.contains("User message"));

        LOGGER.info("System messages excluded test passed");
    }

    @Test
    void testDefaultConfig() {
        ShortTermMemory defaultMemory = new ShortTermMemory();
        assertEquals(20, defaultMemory.getConfig().getMaxMessages());
        assertEquals(4000, defaultMemory.getConfig().getMaxTokens());
        assertTrue(defaultMemory.getConfig().isEnableRollingSummary());

        LOGGER.info("Default config test passed");
    }

    @Test
    void testIsEmpty() {
        assertTrue(memory.isEmpty());
        memory.addMessage(Message.of(RoleType.USER, "Test"));
        assertFalse(memory.isEmpty());

        LOGGER.info("Is empty test passed");
    }
}
