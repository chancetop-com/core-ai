package ai.core.memory.model;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for MemoryEntry and its subclasses.
 *
 * @author xander
 */
class MemoryEntryTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryEntryTest.class);

    @Test
    void testMemoryEntryBuilder() {
        var entry = MemoryEntry.builder()
            .userId("user-1")
            .agentId("agent-1")
            .content("Test memory content")
            .type(MemoryType.SEMANTIC)
            .importance(0.8)
            .strength(0.9)
            .build();

        assertEquals("user-1", entry.getUserId());
        assertEquals("agent-1", entry.getAgentId());
        assertEquals("Test memory content", entry.getContent());
        assertEquals(MemoryType.SEMANTIC, entry.getType());
        assertEquals(0.8, entry.getImportance());
        assertEquals(0.9, entry.getStrength());
        assertNotNull(entry.getId());
        assertNotNull(entry.getCreatedAt());
        assertNotNull(entry.getLastAccessedAt());
        assertEquals(0, entry.getAccessCount());

        LOGGER.info("MemoryEntry builder test passed, id={}", entry.getId());
    }

    @Test
    void testMemoryEntryDefaults() {
        var entry = MemoryEntry.builder()
            .content("Default test")
            .build();

        assertEquals(0.5, entry.getImportance());
        assertEquals(1.0, entry.getStrength());
        assertEquals(0, entry.getAccessCount());
        assertNotNull(entry.getMetadata());
        assertTrue(entry.getMetadata().isEmpty());

        LOGGER.info("MemoryEntry defaults test passed");
    }

    @Test
    void testRecordAccess() {
        var entry = MemoryEntry.builder()
            .content("Test")
            .build();

        Instant before = entry.getLastAccessedAt();
        int countBefore = entry.getAccessCount();

        // Small delay to ensure time difference
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        entry.recordAccess();

        assertEquals(countBefore + 1, entry.getAccessCount());
        assertTrue(entry.getLastAccessedAt().isAfter(before) || entry.getLastAccessedAt().equals(before));

        LOGGER.info("Record access test passed, count={}", entry.getAccessCount());
    }

    @Test
    void testSemanticMemoryEntry() {
        var entry = SemanticMemoryEntry.builder()
            .userId("user-1")
            .content("User prefers dark mode")
            .category(SemanticCategory.PREFERENCE)
            .subject("theme")
            .predicate("prefers")
            .object("dark mode")
            .importance(0.7)
            .build();

        assertEquals(MemoryType.SEMANTIC, entry.getType());
        assertEquals(SemanticCategory.PREFERENCE, entry.getCategory());
        assertEquals("theme", entry.getSubject());
        assertEquals("prefers", entry.getPredicate());
        assertEquals("dark mode", entry.getObject());

        // Test KV key building
        String kvKey = entry.buildKvKey();
        assertEquals("user-1:theme", kvKey);

        LOGGER.info("SemanticMemoryEntry test passed, kvKey={}", kvKey);
    }

    @Test
    void testSemanticMemoryEntryKvKeyWithoutUserId() {
        var entry = SemanticMemoryEntry.builder()
            .content("Some fact")
            .subject("Weather")
            .build();

        String kvKey = entry.buildKvKey();
        assertEquals("weather", kvKey);

        LOGGER.info("SemanticMemoryEntry KV key without userId test passed");
    }

    @Test
    void testSemanticMemoryEntryKvKeyNull() {
        var entry = SemanticMemoryEntry.builder()
            .content("No subject")
            .build();

        assertNull(entry.buildKvKey());

        LOGGER.info("SemanticMemoryEntry KV key null test passed");
    }

    @Test
    void testEpisodicMemoryEntry() {
        var entry = EpisodicMemoryEntry.builder()
            .userId("user-1")
            .sessionId("session-123")
            .situation("User asked about weather")
            .action("Provided weather forecast")
            .outcome("User was satisfied")
            .content("Weather inquiry session")
            .importance(0.6)
            .build();

        assertEquals(MemoryType.EPISODIC, entry.getType());
        assertEquals("session-123", entry.getSessionId());
        assertEquals("User asked about weather", entry.getSituation());
        assertEquals("Provided weather forecast", entry.getAction());
        assertEquals("User was satisfied", entry.getOutcome());

        LOGGER.info("EpisodicMemoryEntry test passed");
    }

    @Test
    void testMemoryEntryWithMetadata() {
        Map<String, Object> metadata = Map.of(
            "source", "conversation",
            "confidence", 0.95
        );

        var entry = MemoryEntry.builder()
            .content("Test with metadata")
            .metadata(metadata)
            .build();

        assertEquals("conversation", entry.getMetadata().get("source"));
        assertEquals(0.95, entry.getMetadata().get("confidence"));

        LOGGER.info("MemoryEntry with metadata test passed");
    }

    @Test
    void testMemoryEntryWithCustomId() {
        var entry = MemoryEntry.builder()
            .id("custom-id-123")
            .content("Test")
            .build();

        assertEquals("custom-id-123", entry.getId());

        LOGGER.info("MemoryEntry with custom ID test passed");
    }

    @Test
    void testMemoryEntrySetters() {
        var entry = MemoryEntry.builder().content("Test").build();

        entry.setUserId("new-user");
        entry.setAgentId("new-agent");
        entry.setStrength(0.5);

        assertEquals("new-user", entry.getUserId());
        assertEquals("new-agent", entry.getAgentId());
        assertEquals(0.5, entry.getStrength());

        LOGGER.info("MemoryEntry setters test passed");
    }

    @Test
    void testMemoryEntryWithTimestamps() {
        Instant createdAt = Instant.now().minusSeconds(3600);
        Instant lastAccessedAt = Instant.now().minusSeconds(1800);

        var entry = MemoryEntry.builder()
            .content("Test")
            .createdAt(createdAt)
            .lastAccessedAt(lastAccessedAt)
            .accessCount(5)
            .build();

        assertEquals(createdAt, entry.getCreatedAt());
        assertEquals(lastAccessedAt, entry.getLastAccessedAt());
        assertEquals(5, entry.getAccessCount());

        LOGGER.info("MemoryEntry with timestamps test passed");
    }
}
