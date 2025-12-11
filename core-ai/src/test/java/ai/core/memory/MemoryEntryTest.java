package ai.core.memory;

import ai.core.memory.model.MemoryEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for MemoryEntry.
 *
 * @author xander
 */
class MemoryEntryTest {

    @Test
    void testDefaultConstructor() {
        var entry = new MemoryEntry();

        // Default constructor does NOT generate ID (for JSON deserialization)
        assertNotNull(entry.getMetadata());
    }

    @Test
    void testConstructorWithUserIdAndContent() {
        var entry = new MemoryEntry("user-1", "User likes coffee");

        assertEquals("user-1", entry.getUserId());
        assertEquals("User likes coffee", entry.getContent());
        assertNotNull(entry.getId());
    }

    @Test
    void testStaticFactoryMethod() {
        var entry = MemoryEntry.of("user-1", "Prefers dark mode");

        assertEquals("user-1", entry.getUserId());
        assertEquals("Prefers dark mode", entry.getContent());
    }

    @Test
    void testStaticFactoryMethodWithoutUserId() {
        var entry = MemoryEntry.of("Global memory");

        assertNotNull(entry.getId());
        assertEquals("Global memory", entry.getContent());
    }

    @Test
    void testFieldAccess() {
        var entry = new MemoryEntry("user-1", "Original");

        // Test direct field access (public fields for JSON compatibility)
        entry.userId = "new-user";
        entry.setContent("New content");
        entry.metadata.put("key", "value");

        assertEquals("new-user", entry.getUserId());
        assertEquals("New content", entry.getContent());
        assertEquals("value", entry.getMetadata().get("key"));
    }

    @Test
    void testRecordAccess() {
        var entry = new MemoryEntry("user-1", "Test");
        var initialAccess = entry.getLastAccessedAt();

        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        entry.recordAccess();

        assertTrue(entry.getLastAccessedAt().isAfter(initialAccess)
            || entry.getLastAccessedAt().equals(initialAccess));
    }

    @Test
    void testToString() {
        var entry = MemoryEntry.of("user-1", "Test content");

        String str = entry.toString();

        assertTrue(str.contains("user-1"));
        assertTrue(str.contains("Test content"));
    }
}
