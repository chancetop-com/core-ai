package ai.core.memory.longterm;

import java.util.List;
import java.util.Optional;

/**
 * @author xander
 */
public interface MemoryStore {

    void save(MemoryRecord record);

    void save(MemoryRecord record, float[] embedding);

    void saveAll(List<MemoryRecord> records, List<float[]> embeddings);

    Optional<MemoryRecord> findById(String id);

    List<MemoryRecord> findByScope(MemoryScope scope);

    List<MemoryRecord> searchByVector(MemoryScope scope, float[] queryEmbedding, int topK);

    List<MemoryRecord> searchByVector(MemoryScope scope, float[] queryEmbedding, int topK, SearchFilter filter);

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
