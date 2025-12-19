package ai.core.memory.longterm.store;

import java.util.List;

/**
 * Interface for memory vector storage.
 * Stores embeddings linked by memory ID.
 *
 * @author xander
 */
public interface MemoryVectorStore {

    /**
     * Save an embedding with its memory ID.
     *
     * @param id        memory record ID
     * @param embedding the vector embedding
     */
    void save(String id, float[] embedding);

    /**
     * Save multiple embeddings.
     *
     * @param ids        memory record IDs
     * @param embeddings the embeddings (same order as IDs)
     */
    void saveAll(List<String> ids, List<float[]> embeddings);

    /**
     * Delete an embedding by ID.
     */
    void delete(String id);

    /**
     * Delete multiple embeddings.
     */
    void deleteAll(List<String> ids);

    /**
     * Search for similar vectors.
     *
     * @param queryEmbedding the query vector
     * @param topK           number of results
     * @return search results with ID and similarity score
     */
    List<VectorSearchResult> search(float[] queryEmbedding, int topK);

    /**
     * Search with ID filter (for user isolation).
     *
     * @param queryEmbedding the query vector
     * @param topK           number of results
     * @param candidateIds   only search within these IDs
     * @return search results with ID and similarity score
     */
    List<VectorSearchResult> search(float[] queryEmbedding, int topK, List<String> candidateIds);

    /**
     * Get total count of stored embeddings.
     */
    int count();
}
