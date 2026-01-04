package ai.core.memory.longterm;

import java.util.List;
import java.util.Optional;

/**
 * Memory store interface supporting vector search and keyword search.
 *
 * @author xander
 */
public interface MemoryStore {

    /**
     * Save memory record without embedding (for keyword-only search).
     */
    void save(MemoryRecord record);

    /**
     * Save memory record with embedding (for vector search).
     */
    void save(MemoryRecord record, float[] embedding);

    void saveAll(List<MemoryRecord> records, List<float[]> embeddings);

    Optional<MemoryRecord> findById(String id);

    List<MemoryRecord> findByScope(MemoryScope scope);

    /**
     * Vector-based semantic search using embedding similarity.
     */
    List<MemoryRecord> searchByVector(MemoryScope scope, float[] queryEmbedding, int topK);

    List<MemoryRecord> searchByVector(MemoryScope scope, float[] queryEmbedding, int topK, SearchFilter filter);

    /**
     * Keyword-based search using text matching.
     */
    List<MemoryRecord> searchByKeyword(MemoryScope scope, String keyword, int topK);

    List<MemoryRecord> searchByKeyword(MemoryScope scope, String keyword, int topK, SearchFilter filter);

    void delete(String id);

    void deleteByScope(MemoryScope scope);

    void recordAccess(List<String> ids);

    void updateDecayFactor(String id, double decayFactor);

    List<MemoryRecord> findDecayed(MemoryScope scope, double threshold);

    int deleteDecayed(double threshold);

    int count(MemoryScope scope);

    int countByType(MemoryScope scope, MemoryType type);
}
