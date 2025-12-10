package ai.core.memory;

import ai.core.memory.model.MemoryEntry;
import ai.core.memory.store.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for MemoryManager.
 *
 * @author xander
 */
class MemoryManagerTest {

    private MemoryManager memoryManager;
    private InMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        memoryManager = new MemoryManager(store, null, null);
    }

    @Test
    void testAddMemory() {
        memoryManager.addMemory("user-1", "User likes coffee");

        var memories = memoryManager.getMemories("user-1", 10);
        assertEquals(1, memories.size());
        assertEquals("User likes coffee", memories.getFirst().getContent());
    }

    @Test
    void testAddMemoryEntry() {
        var entry = MemoryEntry.of("user-1", "Prefers dark mode");

        memoryManager.addMemory(entry);

        var memories = memoryManager.getMemories("user-1", 10);
        assertEquals(1, memories.size());
    }

    @Test
    void testDeleteMemory() {
        var entry = MemoryEntry.of("user-1", "To delete");
        memoryManager.addMemory(entry);

        memoryManager.deleteMemory(entry.getId());

        var memories = memoryManager.getMemories("user-1", 10);
        assertTrue(memories.isEmpty());
    }

    @Test
    void testSearch() {
        memoryManager.addMemory("user-1", "User likes coffee");
        memoryManager.addMemory("user-1", "User prefers tea");

        var results = memoryManager.search("coffee", "user-1", 10);

        assertEquals(1, results.size());
        assertTrue(results.getFirst().getContent().contains("coffee"));
    }

    @Test
    void testBuildContext() {
        memoryManager.addMemory("user-1", "Likes coffee");
        memoryManager.addMemory("user-1", "Prefers morning meetings");

        String context = memoryManager.buildContext("user-1", 10);

        assertTrue(context.contains("[User Memory]"));
        assertFalse(context.isEmpty());
    }

    @Test
    void testBuildContextEmpty() {
        String context = memoryManager.buildContext("user-1", 10);
        assertEquals("", context);
    }

    @Test
    void testGetStore() {
        assertNotNull(memoryManager.getStore());
        assertEquals(store, memoryManager.getStore());
    }

    @Test
    void testIsEnabled() {
        assertTrue(memoryManager.isEnabled());
    }

    @Test
    void testDisabledManager() {
        var disabledManager = new MemoryManager(store, null, null, null, false);
        assertFalse(disabledManager.isEnabled());
    }

    @Test
    void testProcessConversationWithoutLLM() {
        // Without LLM provider, should not extract anything but not throw
        memoryManager.processConversation(null, "user-1");

        var memories = memoryManager.getMemories("user-1", 10);
        assertTrue(memories.isEmpty());
    }

    @Test
    void testMemoryLimit() {
        for (int i = 0; i < 10; i++) {
            memoryManager.addMemory("user-1", "Memory " + i);
        }

        var memories = memoryManager.getMemories("user-1", 5);
        assertEquals(5, memories.size());

        var allMemories = memoryManager.getMemories("user-1", 100);
        assertEquals(10, allMemories.size());
    }

    @Test
    void testMultipleUsers() {
        memoryManager.addMemory("user-1", "User 1 memory");
        memoryManager.addMemory("user-2", "User 2 memory");

        var user1Memories = memoryManager.getMemories("user-1", 10);
        var user2Memories = memoryManager.getMemories("user-2", 10);

        assertEquals(1, user1Memories.size());
        assertEquals(1, user2Memories.size());
        assertEquals("User 1 memory", user1Memories.getFirst().getContent());
        assertEquals("User 2 memory", user2Memories.getFirst().getContent());
    }
}
