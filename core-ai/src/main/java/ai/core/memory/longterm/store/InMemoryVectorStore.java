package ai.core.memory.longterm.store;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of MemoryVectorStore.
 * Uses brute-force cosine similarity search.
 * For development and testing purposes.
 *
 * @author xander
 */
public class InMemoryVectorStore implements MemoryVectorStore {

    private final Map<String, float[]> embeddings = new ConcurrentHashMap<>();

    @Override
    public void save(String id, float[] embedding) {
        embeddings.put(id, embedding);
    }

    @Override
    public void saveAll(List<String> ids, List<float[]> embeddingList) {
        for (int i = 0; i < ids.size(); i++) {
            embeddings.put(ids.get(i), embeddingList.get(i));
        }
    }

    @Override
    public void delete(String id) {
        embeddings.remove(id);
    }

    @Override
    public void deleteAll(List<String> ids) {
        for (String id : ids) {
            embeddings.remove(id);
        }
    }

    @Override
    public List<VectorSearchResult> search(float[] queryEmbedding, int topK) {
        return embeddings.entrySet().stream()
            .map(e -> new VectorSearchResult(e.getKey(), cosineSimilarity(queryEmbedding, e.getValue())))
            .sorted(Comparator.comparingDouble(VectorSearchResult::similarity).reversed())
            .limit(topK)
            .toList();
    }

    @Override
    public List<VectorSearchResult> search(float[] queryEmbedding, int topK, List<String> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return search(queryEmbedding, topK);
        }

        return candidateIds.stream()
            .filter(embeddings::containsKey)
            .map(id -> new VectorSearchResult(id, cosineSimilarity(queryEmbedding, embeddings.get(id))))
            .sorted(Comparator.comparingDouble(VectorSearchResult::similarity).reversed())
            .limit(topK)
            .toList();
    }

    @Override
    public int count() {
        return embeddings.size();
    }

    /**
     * Calculate cosine similarity between two vectors.
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
