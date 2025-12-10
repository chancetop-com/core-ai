package ai.core.memory;

import ai.core.document.Embedding;
import ai.core.memory.model.MemoryEntry;

import java.util.List;
import java.util.Optional;

/**
 * Simple long-term memory interface for persistent cross-session memory storage.
 *
 * @author xander
 */
public interface LongTermMemory {

    /**
     * Add a memory entry to the store.
     *
     * @param entry the memory entry to add
     */
    void add(MemoryEntry entry);

    /**
     * Add a simple text memory for a user.
     *
     * @param userId  the user ID
     * @param content the memory content
     */
    default void add(String userId, String content) {
        add(MemoryEntry.of(userId, content));
    }

    /**
     * Update an existing memory entry.
     *
     * @param memoryId the ID of the memory to update
     * @param entry    the updated memory entry
     */
    void update(String memoryId, MemoryEntry entry);

    /**
     * Delete a memory entry by ID.
     *
     * @param memoryId the ID of the memory to delete
     */
    void delete(String memoryId);

    /**
     * Get a memory entry by ID.
     *
     * @param memoryId the memory ID
     * @return optional memory entry
     */
    Optional<MemoryEntry> getById(String memoryId);

    /**
     * Get all memories for a user.
     *
     * @param userId the user ID
     * @param limit  maximum number of results
     * @return list of memory entries
     */
    List<MemoryEntry> getByUserId(String userId, int limit);

    /**
     * Search memories by text query for a user.
     *
     * @param query  the search query
     * @param userId the user ID (null for all users)
     * @param topK   maximum number of results
     * @return list of relevant memory entries
     */
    List<MemoryEntry> search(String query, String userId, int topK);

    /**
     * Find similar memories by embedding vector.
     *
     * @param embedding the query embedding
     * @param userId    the user ID (null for all users)
     * @param topK      maximum number of results
     * @param threshold minimum similarity score (0.0 to 1.0)
     * @return list of similar memory entries
     */
    List<MemoryEntry> findSimilar(Embedding embedding, String userId, int topK, double threshold);

    /**
     * Get all memories in the store.
     *
     * @return list of all memory entries
     */
    List<MemoryEntry> getAll();

    /**
     * Get the total number of memories in the store.
     *
     * @return memory count
     */
    int size();

    /**
     * Clear all memories from the store.
     */
    void clear();

    /**
     * Build a context string from all memories for a user.
     *
     * @param userId the user ID
     * @param limit  maximum number of memories to include
     * @return formatted context string
     */
    default String buildContext(String userId, int limit) {
        var memories = getByUserId(userId, limit);
        if (memories.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder("[User Memory]\n");
        for (var memory : memories) {
            sb.append("- ").append(memory.getContent()).append('\n');
        }
        return sb.toString();
    }
}
