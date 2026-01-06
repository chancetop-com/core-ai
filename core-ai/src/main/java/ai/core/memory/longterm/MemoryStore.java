package ai.core.memory.longterm;

import java.util.List;
import java.util.Optional;

/**
 * @author xander
 */
public interface MemoryStore {

    void save(MemoryRecord record);

    void save(MemoryRecord record, List<Double> embedding);

    void saveAll(List<MemoryRecord> records, List<List<Double>> embeddings);

    Optional<MemoryRecord> findById(String id);

    List<MemoryRecord> findAll();

    List<MemoryRecord> searchByVector(List<Double> queryEmbedding, int topK);

    List<MemoryRecord> searchByVector(List<Double> queryEmbedding, int topK, SearchFilter filter);

    List<MemoryRecord> searchByKeyword(String keyword, int topK);

    List<MemoryRecord> searchByKeyword(String keyword, int topK, SearchFilter filter);

    void delete(String id);

    void deleteAll();

    void recordAccess(List<String> ids);

    void updateDecayFactor(String id, double decayFactor);

    List<MemoryRecord> findDecayed(double threshold);

    int deleteDecayed(double threshold);

    int count();
}
