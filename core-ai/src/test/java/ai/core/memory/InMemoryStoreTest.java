package ai.core.memory;

import ai.core.document.Embedding;
import ai.core.memory.model.MemoryEntry;
import ai.core.memory.store.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for InMemoryStore.
 *
 * @author xander
 */
class InMemoryStoreTest {

    private InMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
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
    void testAddNull() {
        store.add(null);
        assertEquals(0, store.size());
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
    void testGetByUserIdWithLimit() {
        store.add(MemoryEntry.of("user-1", "Memory 1"));
        store.add(MemoryEntry.of("user-1", "Memory 2"));
        store.add(MemoryEntry.of("user-1", "Memory 3"));

        var results = store.getByUserId("user-1", 2);

        assertEquals(2, results.size());
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
    void testSearchEmptyQuery() {
        store.add(MemoryEntry.of("user-1", "Memory 1"));
        store.add(MemoryEntry.of("user-1", "Memory 2"));

        var results = store.search("", "user-1", 10);

        assertEquals(2, results.size());
    }

    @Test
    void testFindSimilarWithEmbeddings() {
        var entry1 = new MemoryEntry("user-1", "Similar 1");
        entry1.setEmbedding(new Embedding(List.of(1.0, 0.0, 0.0)));

        var entry2 = new MemoryEntry("user-1", "Different");
        entry2.setEmbedding(new Embedding(List.of(0.0, 1.0, 0.0)));

        store.add(entry1);
        store.add(entry2);

        var queryEmbedding = new Embedding(List.of(1.0, 0.0, 0.0));
        var results = store.findSimilar(queryEmbedding, "user-1", 1, 0.9);

        assertEquals(1, results.size());
        assertEquals("Similar 1", results.getFirst().getContent());
    }

    @Test
    void testFindSimilarNullEmbedding() {
        var results = store.findSimilar(null, "user-1", 10, 0.5);
        assertTrue(results.isEmpty());
    }

    @Test
    void testGetAll() {
        store.add(MemoryEntry.of("user-1", "Memory 1"));
        store.add(MemoryEntry.of("user-2", "Memory 2"));

        var all = store.getAll();

        assertEquals(2, all.size());
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
    void testClear() {
        store.add(MemoryEntry.of("user-1", "Memory 1"));
        store.add(MemoryEntry.of("user-1", "Memory 2"));

        store.clear();

        assertEquals(0, store.size());
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
