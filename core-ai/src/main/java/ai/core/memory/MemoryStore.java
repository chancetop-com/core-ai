package ai.core.memory;

import java.util.List;
import java.util.Optional;

/**
 * Storage interface for memory records with user-level isolation.
 * All operations are scoped by userId.
 *
 * @author xander
 */
public interface MemoryStore {

    void save(String userId, MemoryRecord record);

    void save(String userId, MemoryRecord record, List<Double> embedding);

    void saveAll(String userId, List<MemoryRecord> records, List<List<Double>> embeddings);

    Optional<MemoryRecord> findById(String userId, String id);

    List<MemoryRecord> findAll(String userId);

    List<MemoryRecord> searchByVector(String userId, List<Double> queryEmbedding, int topK);

    List<MemoryRecord> searchByVector(String userId, List<Double> queryEmbedding, int topK, SearchFilter filter);

    List<MemoryRecord> searchByKeyword(String userId, String keyword, int topK);

    List<MemoryRecord> searchByKeyword(String userId, String keyword, int topK, SearchFilter filter);

    void delete(String userId, String id);

    void deleteAll(String userId);

    void recordAccess(String userId, List<String> ids);

    void updateDecayFactor(String userId, String id, double decayFactor);

    List<MemoryRecord> findDecayed(String userId, double threshold);

    int deleteDecayed(String userId, double threshold);

    int count(String userId);
}
