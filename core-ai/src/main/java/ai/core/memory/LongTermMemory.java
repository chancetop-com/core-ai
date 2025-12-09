package ai.core.memory;

import ai.core.document.Embedding;
import ai.core.memory.model.MemoryEntry;
import ai.core.memory.model.MemoryFilter;
import ai.core.memory.model.MemoryType;
import ai.core.memory.decay.MemoryDecayPolicy;

import java.util.List;
import java.util.Optional;

/**
 * Long-term memory interface for persistent cross-session memory storage.
 * Supports semantic memory (facts, preferences, knowledge) and episodic memory (events, situations).
 *
 * @author xander
 */
public interface LongTermMemory extends MemoryStore {

    /**
     * Add a memory entry to the store.
     *
     * @param entry the memory entry to add
     */
    void add(MemoryEntry entry);

    /**
     * Add multiple memory entries in batch.
     *
     * @param entries the memory entries to add
     */
    void addBatch(List<MemoryEntry> entries);

    /**
     * Update an existing memory entry.
     *
     * @param memoryId the ID of the memory to update
     * @param entry    the updated memory entry
     */
    void update(String memoryId, MemoryEntry entry);

    /**
     * Update only the access metadata of a memory entry (lastAccessedAt, accessCount).
     *
     * @param entry the memory entry with updated metadata
     */
    void updateMetadata(MemoryEntry entry);

    /**
     * Delete a memory entry by ID.
     *
     * @param memoryId the ID of the memory to delete
     */
    void delete(String memoryId);

    /**
     * Delete multiple memory entries by IDs.
     *
     * @param memoryIds the IDs of memories to delete
     */
    void deleteBatch(List<String> memoryIds);

    /**
     * Retrieve memories relevant to the query.
     *
     * @param query  the query string
     * @param topK   maximum number of results
     * @param filter filter criteria
     * @return list of relevant memory entries
     */
    List<MemoryEntry> retrieve(String query, int topK, MemoryFilter filter);

    /**
     * Find similar memories by embedding vector.
     *
     * @param embedding similarity threshold
     * @param topK      maximum number of results
     * @param threshold minimum similarity score
     * @return list of similar memory entries
     */
    List<MemoryEntry> findSimilar(Embedding embedding, int topK, double threshold);

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
     * @param type   optional memory type filter
     * @param limit  maximum number of results
     * @return list of memory entries
     */
    List<MemoryEntry> getByUserId(String userId, MemoryType type, int limit);

    /**
     * Apply decay policy to all memories and return decayed memories.
     *
     * @param policy the decay policy to apply
     */
    void applyDecay(MemoryDecayPolicy policy);

    /**
     * Get memories with strength below the threshold (candidates for removal).
     *
     * @param minStrength minimum strength threshold
     * @return list of decayed memory entries
     */
    List<MemoryEntry> getDecayedMemories(double minStrength);

    /**
     * Remove all memories with strength below the threshold.
     *
     * @param minStrength minimum strength threshold
     * @return number of removed memories
     */
    int removeDecayedMemories(double minStrength);
}
