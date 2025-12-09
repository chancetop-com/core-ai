package ai.core.memory.store;

import ai.core.document.Embedding;
import ai.core.memory.model.MemoryEntry;
import ai.core.memory.model.MemoryFilter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of VectorMemoryStore using brute-force cosine similarity.
 * Suitable for development, testing, or small-scale deployments.
 *
 * @author xander
 */
public class InMemoryVectorStore implements VectorMemoryStore {
    private final Map<String, MemoryEntry> store = new ConcurrentHashMap<>();

    @Override
    public void save(MemoryEntry entry) {
        if (entry != null && entry.getId() != null) {
            store.put(entry.getId(), entry);
        }
    }

    @Override
    public void saveBatch(List<MemoryEntry> entries) {
        if (entries != null) {
            entries.forEach(this::save);
        }
    }

    @Override
    public void update(String id, MemoryEntry entry) {
        if (id != null && entry != null) {
            entry.setId(id);
            store.put(id, entry);
        }
    }

    @Override
    public void delete(String id) {
        store.remove(id);
    }

    @Override
    public void deleteBatch(List<String> ids) {
        if (ids != null) {
            ids.forEach(store::remove);
        }
    }

    @Override
    public Optional<MemoryEntry> get(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<ScoredMemory> similaritySearch(Embedding queryEmbedding, int topK, double threshold, MemoryFilter filter) {
        if (queryEmbedding == null || queryEmbedding.vectors() == null) {
            return List.of();
        }

        return store.values().stream()
            .filter(entry -> entry.getEmbedding() != null && entry.getEmbedding().vectors() != null)
            .filter(entry -> filter == null || filter.matches(entry))
            .map(entry -> {
                double score = cosineSimilarity(queryEmbedding.vectors(), entry.getEmbedding().vectors());
                return new ScoredMemory(entry, score);
            })
            .filter(sm -> sm.score() >= threshold)
            .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
            .limit(topK)
            .toList();
    }

    @Override
    public List<MemoryEntry> findAll(MemoryFilter filter, int limit) {
        return store.values().stream()
            .filter(entry -> filter == null || filter.matches(entry))
            .limit(limit)
            .toList();
    }

    @Override
    public int count(MemoryFilter filter) {
        if (filter == null) {
            return store.size();
        }
        return (int) store.values().stream()
            .filter(filter::matches)
            .count();
    }

    /**
     * Calculate cosine similarity between two vectors.
     */
    private double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a.size() != b.size()) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.size(); i++) {
            dotProduct += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Get all entries (for testing/debugging).
     */
    public List<MemoryEntry> getAll() {
        return new ArrayList<>(store.values());
    }

    /**
     * Clear all entries.
     */
    public void clear() {
        store.clear();
    }

    /**
     * Get total count.
     */
    public int size() {
        return store.size();
    }
}
