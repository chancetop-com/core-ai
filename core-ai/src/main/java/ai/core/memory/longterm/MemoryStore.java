package ai.core.memory.longterm;

import java.util.List;
import java.util.Optional;

/**
 * @author xander
 */
public interface MemoryStore {

    void save(MemoryRecord record, float[] embedding);

    void saveAll(List<MemoryRecord> records, List<float[]> embeddings);

    Optional<MemoryRecord> findById(String id);

    List<MemoryRecord> findByNamespace(Namespace namespace);

    List<MemoryRecord> search(Namespace namespace, float[] queryEmbedding, int topK);

    List<MemoryRecord> search(Namespace namespace, float[] queryEmbedding, int topK, SearchFilter filter);

    void delete(String id);

    void deleteByNamespace(Namespace namespace);

    void recordAccess(List<String> ids);

    void updateDecayFactor(String id, double decayFactor);

    List<MemoryRecord> findDecayed(Namespace namespace, double threshold);

    int deleteDecayed(double threshold);

    int count(Namespace namespace);

    int countByType(Namespace namespace, MemoryType type);
}
