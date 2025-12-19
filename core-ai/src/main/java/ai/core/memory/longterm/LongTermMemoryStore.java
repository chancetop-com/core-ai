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
     * Delete all memories for a user.
     */
    void deleteByUserId(String userId);

    // ==================== Search ====================

    /**
     * Search memories by vector similarity.
     *
     * @param userId         the user ID for isolation
     * @param queryEmbedding the query vector
     * @param topK           number of results to return
     * @return ranked memory records
     */
    List<MemoryRecord> search(String userId, float[] queryEmbedding, int topK);

    /**
     * Search memories with filter.
     *
     * @param userId         the user ID for isolation
     * @param queryEmbedding the query vector
     * @param topK           number of results to return
     * @param filter         additional filter criteria
     * @return ranked memory records
     */
    List<MemoryRecord> search(String userId, float[] queryEmbedding, int topK, SearchFilter filter);

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
     * @param userId    the user ID
     * @param threshold decay factor threshold
     * @return memories below threshold
     */
    List<MemoryRecord> getDecayedMemories(String userId, double threshold);

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
     * Get total memory count for a user.
     */
    int count(String userId);

    /**
     * Get memory count by type for a user.
     */
    int countByType(String userId, MemoryType type);
}
