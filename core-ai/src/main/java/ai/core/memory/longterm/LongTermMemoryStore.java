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
     * Search memories with filter within a namespace.
     *
     * @param namespace      the namespace for scoping
     * @param queryEmbedding the query vector
     * @param topK           number of results to return
     * @param filter         additional filter criteria
     * @return ranked memory records
     */
    List<MemoryRecord> search(Namespace namespace, float[] queryEmbedding, int topK, SearchFilter filter);

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
     * Clean up memories below the decay threshold.
     *
     * @param threshold decay factor threshold
     * @return number of deleted memories
     */
    int cleanupDecayed(double threshold);

    // ==================== Statistics ====================

    /**
     * Get total memory count in a namespace.
     *
     * @param namespace the namespace to count
     * @return number of memories
     */
    int count(Namespace namespace);

    /**
     * Get memory count by type in a namespace.
     *
     * @param namespace the namespace to count
     * @param type      the memory type
     * @return number of memories of that type
     */
    int countByType(Namespace namespace, MemoryType type);
}
