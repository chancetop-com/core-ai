package ai.core.memory.longterm.store;

import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.MemoryType;
import ai.core.memory.longterm.SearchFilter;

import java.util.List;
import java.util.Optional;

/**
 * Interface for memory metadata storage (SQL layer).
 * Stores all memory fields except embedding.
 *
 * @author xander
 */
public interface MemoryMetadataStore {

    // ==================== CRUD ====================

    void save(MemoryRecord record);

    void saveAll(List<MemoryRecord> records);

    Optional<MemoryRecord> findById(String id);

    List<MemoryRecord> findByIds(List<String> ids);

    List<MemoryRecord> findByUserId(String userId);

    void delete(String id);

    void deleteByUserId(String userId);

    // ==================== Query ====================

    /**
     * Find memories by user with filter.
     */
    List<MemoryRecord> findByUserIdWithFilter(String userId, SearchFilter filter);

    /**
     * Find memories below decay threshold.
     */
    List<MemoryRecord> findDecayed(String userId, double threshold);

    /**
     * Find memories below decay threshold for all users.
     */
    List<MemoryRecord> findAllDecayed(double threshold);

    /**
     * Find all memory records.
     */
    List<MemoryRecord> findAll();

    // ==================== Update ====================

    /**
     * Increment access count and update lastAccessedAt.
     */
    void recordAccess(String id);

    void recordAccess(List<String> ids);

    /**
     * Update decay factor for a memory.
     */
    void updateDecayFactor(String id, double decayFactor);

    /**
     * Batch update decay factors.
     */
    void updateDecayFactors(List<String> ids, List<Double> decayFactors);

    // ==================== Statistics ====================

    int count(String userId);

    int countByType(String userId, MemoryType type);

    int countAll();
}
