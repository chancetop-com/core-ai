package ai.core.memory.store;

import ai.core.document.Embedding;
import ai.core.memory.model.MemoryEntry;
import ai.core.memory.model.MemoryFilter;

import java.util.List;
import java.util.Optional;

/**
 * Vector store interface for semantic similarity search on memories.
 *
 * @author xander
 */
public interface VectorMemoryStore {

    /**
     * Save a memory entry with its embedding.
     *
     * @param entry the memory entry to save
     */
    void save(MemoryEntry entry);

    /**
     * Save multiple memory entries in batch.
     *
     * @param entries the memory entries to save
     */
    void saveBatch(List<MemoryEntry> entries);

    /**
     * Update an existing memory entry.
     *
     * @param id    the memory ID
     * @param entry the updated memory entry
     */
    void update(String id, MemoryEntry entry);

    /**
     * Delete a memory entry by ID.
     *
     * @param id the memory ID
     */
    void delete(String id);

    /**
     * Delete multiple memory entries by IDs.
     *
     * @param ids the memory IDs
     */
    void deleteBatch(List<String> ids);

    /**
     * Get a memory entry by ID.
     *
     * @param id the memory ID
     * @return optional memory entry
     */
    Optional<MemoryEntry> get(String id);

    /**
     * Search for similar memories by embedding.
     *
     * @param queryEmbedding the query embedding vector
     * @param topK           maximum number of results
     * @param threshold      minimum similarity score (0-1)
     * @param filter         optional filter criteria
     * @return list of similar memory entries with scores
     */
    List<ScoredMemory> similaritySearch(Embedding queryEmbedding, int topK, double threshold, MemoryFilter filter);

    /**
     * Get all memory entries matching the filter.
     *
     * @param filter filter criteria
     * @param limit  maximum number of results
     * @return list of memory entries
     */
    List<MemoryEntry> findAll(MemoryFilter filter, int limit);

    /**
     * Get total count of memories matching the filter.
     *
     * @param filter filter criteria
     * @return count
     */
    int count(MemoryFilter filter);

    /**
     * Memory entry with similarity score.
     */
    record ScoredMemory(MemoryEntry entry, double score) { }
}
