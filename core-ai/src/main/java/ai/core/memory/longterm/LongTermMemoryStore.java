package ai.core.memory.longterm;

import java.util.List;
import java.util.Optional;

/**
 * Interface for long-term memory storage.
 * Coordinates metadata store and vector store for memory operations.
 *
 * @author xander
 */
public interface LongTermMemoryStore {

    // ==================== Basic CRUD ====================

    /**
     * Save a memory record with its embedding.
     *
     * @param record    the memory record (metadata)
     * @param embedding the vector embedding
     */
    void save(MemoryRecord record, float[] embedding);

    /**
     * Save multiple memory records with embeddings.
     *
     * @param records    the memory records
     * @param embeddings the embeddings (same order as records)
     */
    void saveAll(List<MemoryRecord> records, List<float[]> embeddings);

    /**
     * Find a memory record by ID.
     */
    Optional<MemoryRecord> findById(String id);

    /**
     * Delete a memory record by ID.
     */
    void delete(String id);

    /**
     * Delete all memories in a namespace.
     *
     * @param namespace the namespace to delete
     */
    void deleteByNamespace(Namespace namespace);

    /**
     * Delete all memories for a user.
     *
     * @deprecated Use {@link #deleteByNamespace(Namespace)} instead
     */
    @Deprecated
    default void deleteByUserId(String userId) {
        deleteByNamespace(Namespace.forUser(userId));
    }

    // ==================== Search ====================

    /**
     * Search memories by vector similarity within a namespace.
     *
     * @param namespace      the namespace for scoping
     * @param queryEmbedding the query vector
     * @param topK           number of results to return
     * @return ranked memory records
     */
    List<MemoryRecord> search(Namespace namespace, float[] queryEmbedding, int topK);

    /**
     * Search memories by vector similarity.
     *
     * @deprecated Use {@link #search(Namespace, float[], int)} instead
     */
    @Deprecated
    default List<MemoryRecord> search(String userId, float[] queryEmbedding, int topK) {
        return search(Namespace.forUser(userId), queryEmbedding, topK);
    }

    /**
     * Search memories with filter within a namespace.
     *
     * @param namespace      the namespace for scoping
     * @param queryEmbedding the query vector
     * @param topK           number of results to return
     * @param filter         additional filter criteria
     * @return ranked memory records
     */
    List<MemoryRecord> search(Namespace namespace, float[] queryEmbedding, int topK, SearchFilter filter);

    /**
     * Search memories with filter.
     *
     * @deprecated Use {@link #search(Namespace, float[], int, SearchFilter)} instead
     */
    @Deprecated
    default List<MemoryRecord> search(String userId, float[] queryEmbedding, int topK, SearchFilter filter) {
        return search(Namespace.forUser(userId), queryEmbedding, topK, filter);
    }

    // ==================== Access Tracking ====================

    /**
     * Record access to memories (increments access count).
     *
     * @param ids the memory IDs that were accessed
     */
    void recordAccess(List<String> ids);

    // ==================== Decay Management ====================

    /**
     * Update decay factors for all memories.
     * Should be called periodically (e.g., daily).
     */
    void updateDecay();

    /**
     * Get memories below the decay threshold (candidates for cleanup).
     *
     * @param namespace the namespace to check
     * @param threshold decay factor threshold
     * @return memories below threshold
     */
    List<MemoryRecord> getDecayedMemories(Namespace namespace, double threshold);

    /**
     * Get memories below the decay threshold.
     *
     * @deprecated Use {@link #getDecayedMemories(Namespace, double)} instead
     */
    @Deprecated
    default List<MemoryRecord> getDecayedMemories(String userId, double threshold) {
        return getDecayedMemories(Namespace.forUser(userId), threshold);
    }

    /**
     * Clean up memories below the decay threshold.
     *
     * @param threshold decay factor threshold
     * @return number of deleted memories
     */
    int cleanupDecayed(double threshold);

    // ==================== Raw Conversation Storage ====================

    /**
     * Save raw conversation record (if enabled in config).
     *
     * @param record the raw conversation record
     */
    void saveRawConversation(RawConversationRecord record);

    /**
     * Get raw conversation by ID.
     */
    Optional<RawConversationRecord> getRawConversation(String id);

    /**
     * Get source conversation for a memory record.
     *
     * @param memory the memory record
     * @return source messages, or empty list if not available
     */
    List<ai.core.llm.domain.Message> getSourceConversation(MemoryRecord memory);

    /**
     * Clean up expired raw conversations.
     *
     * @return number of deleted records
     */
    int cleanupExpiredRawConversations();

    // ==================== Statistics ====================

    /**
     * Get total memory count in a namespace.
     *
     * @param namespace the namespace to count
     * @return number of memories
     */
    int count(Namespace namespace);

    /**
     * Get total memory count for a user.
     *
     * @deprecated Use {@link #count(Namespace)} instead
     */
    @Deprecated
    default int count(String userId) {
        return count(Namespace.forUser(userId));
    }

    /**
     * Get memory count by type in a namespace.
     *
     * @param namespace the namespace to count
     * @param type      the memory type
     * @return number of memories of that type
     */
    int countByType(Namespace namespace, MemoryType type);

    /**
     * Get memory count by type for a user.
     *
     * @deprecated Use {@link #countByType(Namespace, MemoryType)} instead
     */
    @Deprecated
    default int countByType(String userId, MemoryType type) {
        return countByType(Namespace.forUser(userId), type);
    }
}
