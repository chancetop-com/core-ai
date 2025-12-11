package ai.core.memory;

import ai.core.memory.model.MemoryEntry;
import ai.core.memory.store.JsonFileStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for JsonFileStore.
 *
 * @author xander
 */
class JsonFileStoreTest {

    @TempDir
    Path tempDir;

    private JsonFileStore store;
    private Path storagePath;

    @BeforeEach
    void setUp() {
        storagePath = tempDir.resolve("test_memories.json");
        store = new JsonFileStore(storagePath, null, true);
    }

    @AfterEach
    void tearDown() throws Exception {
        store.clear();
    }

    @Test
    void testAddAndGet() {
        var entry = MemoryEntry.of("user-1", "Test content");

        store.add(entry);

        var result = store.getById(entry.getId());
        assertTrue(result.isPresent());
        assertEquals("Test content", result.get().getContent());
    }

    @Test
    void testPersistence() throws Exception {
        // Add memory
        var entry = MemoryEntry.of("user-1", "Persistent memory");
        store.add(entry);
        String memoryId = entry.getId();

        // Verify file exists
        assertTrue(Files.exists(storagePath));

        // Create new store from same file
        var newStore = new JsonFileStore(storagePath, null, true);

        // Verify memory was loaded
        var result = newStore.getById(memoryId);
        assertTrue(result.isPresent());
        assertEquals("Persistent memory", result.get().getContent());
    }

    @Test
    void testUpdate() {
        var entry = MemoryEntry.of("user-1", "Original");
        store.add(entry);

        var updated = MemoryEntry.of("user-1", "Updated");
        store.update(entry.getId(), updated);

        var result = store.getById(entry.getId());
        assertTrue(result.isPresent());
        assertEquals("Updated", result.get().getContent());
    }

    @Test
    void testDelete() {
        var entry = MemoryEntry.of("user-1", "To delete");
        store.add(entry);

        store.delete(entry.getId());

        assertFalse(store.getById(entry.getId()).isPresent());
    }

    @Test
    void testGetByUserId() {
        store.add(MemoryEntry.of("user-1", "Memory 1"));
        store.add(MemoryEntry.of("user-1", "Memory 2"));
        store.add(MemoryEntry.of("user-2", "Memory 3"));

        var results = store.getByUserId("user-1", 10);

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(e -> "user-1".equals(e.getUserId())));
    }

    @Test
    void testSearchKeyword() {
        store.add(MemoryEntry.of("user-1", "User likes coffee"));
        store.add(MemoryEntry.of("user-1", "User prefers tea"));

        var results = store.search("coffee", "user-1", 10);

        assertEquals(1, results.size());
        assertTrue(results.getFirst().getContent().contains("coffee"));
    }

    @Test
    void testClear() {
        store.add(MemoryEntry.of("user-1", "Memory 1"));
        store.add(MemoryEntry.of("user-1", "Memory 2"));

        store.clear();

        assertEquals(0, store.size());
    }

    @Test
    void testSize() {
        assertEquals(0, store.size());

        store.add(MemoryEntry.of("user-1", "Memory 1"));
        assertEquals(1, store.size());

        store.add(MemoryEntry.of("user-1", "Memory 2"));
        assertEquals(2, store.size());
    }

    @Test
    void testGetAll() {
        store.add(MemoryEntry.of("user-1", "Memory 1"));
        store.add(MemoryEntry.of("user-2", "Memory 2"));

        var all = store.getAll();

        assertEquals(2, all.size());
    }

    @Test
    void testReload() {
        store.add(MemoryEntry.of("user-1", "Memory 1"));
        String memoryId = store.getAll().getFirst().getId();

        // Clear in-memory but not file
        store = new JsonFileStore(storagePath, null, false);
        store.add(MemoryEntry.of("user-1", "Memory 2"));

        // Reload from file
        store.reload();

        // Should have both memories
        assertTrue(store.getById(memoryId).isPresent());
    }

    @Test
    void testBuildContext() {
        store.add(MemoryEntry.of("user-1", "Likes coffee"));
        store.add(MemoryEntry.of("user-1", "Prefers dark mode"));

        String context = store.buildContext("user-1", 10);

        assertTrue(context.contains("[User Memory]"));
        assertTrue(context.contains("Likes coffee") || context.contains("Prefers dark mode"));
    }

    @Test
    void testBuildContextEmpty() {
        String context = store.buildContext("user-1", 10);
        assertEquals("", context);
    }
}
