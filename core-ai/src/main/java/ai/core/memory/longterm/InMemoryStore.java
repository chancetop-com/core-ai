package ai.core.memory.longterm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author xander
 */
public class InMemoryStore implements MemoryStore {

    private final Map<String, MemoryRecord> records = new ConcurrentHashMap<>();
    private final Map<String, float[]> embeddings = new ConcurrentHashMap<>();

    @Override
    public void save(MemoryRecord record, float[] embedding) {
        records.put(record.getId(), record);
        embeddings.put(record.getId(), embedding);
    }

    @Override
    public void saveAll(List<MemoryRecord> recordList, List<float[]> embeddingList) {
        if (recordList.size() != embeddingList.size()) {
            throw new IllegalArgumentException("Records and embeddings must have same size");
        }
        for (int i = 0; i < recordList.size(); i++) {
            save(recordList.get(i), embeddingList.get(i));
        }
    }

    @Override
    public Optional<MemoryRecord> findById(String id) {
        return Optional.ofNullable(records.get(id));
    }

    @Override
    public List<MemoryRecord> findByNamespace(Namespace namespace) {
        String path = namespace.toPath();
        return records.values().stream()
            .filter(r -> matchesNamespace(r, path))
            .toList();
    }

    @Override
    public List<MemoryRecord> search(Namespace namespace, float[] queryEmbedding, int topK) {
        return search(namespace, queryEmbedding, topK, null);
    }

    @Override
    public List<MemoryRecord> search(Namespace namespace, float[] queryEmbedding, int topK, SearchFilter filter) {
        String path = namespace.toPath();

        List<ScoredRecord> scored = new ArrayList<>();
        for (MemoryRecord record : records.values()) {
            if (!matchesNamespace(record, path)) continue;
            if (filter != null && !filter.matches(record)) continue;

            float[] embedding = embeddings.get(record.getId());
            if (embedding == null) continue;

            double similarity = cosineSimilarity(queryEmbedding, embedding);
            double effectiveScore = record.calculateEffectiveScore(similarity);
            scored.add(new ScoredRecord(record, effectiveScore));
        }

        List<MemoryRecord> results = scored.stream()
            .sorted(Comparator.comparingDouble(ScoredRecord::score).reversed())
            .limit(topK)
            .map(ScoredRecord::record)
            .toList();

        if (!results.isEmpty()) {
            recordAccess(results.stream().map(MemoryRecord::getId).toList());
        }

        return results;
    }

    @Override
    public void delete(String id) {
        records.remove(id);
        embeddings.remove(id);
    }

    @Override
    public void deleteByNamespace(Namespace namespace) {
        String path = namespace.toPath();
        List<String> idsToDelete = records.values().stream()
            .filter(r -> matchesNamespace(r, path))
            .map(MemoryRecord::getId)
            .toList();
        idsToDelete.forEach(this::delete);
    }

    @Override
    public void recordAccess(List<String> ids) {
        for (String id : ids) {
            MemoryRecord record = records.get(id);
            if (record != null) {
                record.incrementAccessCount();
            }
        }
    }

    @Override
    public void updateDecayFactor(String id, double decayFactor) {
        MemoryRecord record = records.get(id);
        if (record != null) {
            record.setDecayFactor(decayFactor);
        }
    }

    @Override
    public List<MemoryRecord> findDecayed(Namespace namespace, double threshold) {
        String path = namespace.toPath();
        return records.values().stream()
            .filter(r -> matchesNamespace(r, path))
            .filter(r -> r.getDecayFactor() < threshold)
            .toList();
    }

    @Override
    public int deleteDecayed(double threshold) {
        List<String> idsToDelete = records.values().stream()
            .filter(r -> r.getDecayFactor() < threshold)
            .map(MemoryRecord::getId)
            .toList();
        idsToDelete.forEach(this::delete);
        return idsToDelete.size();
    }

    @Override
    public int count(Namespace namespace) {
        String path = namespace.toPath();
        return (int) records.values().stream()
            .filter(r -> matchesNamespace(r, path))
            .count();
    }

    @Override
    public int countByType(Namespace namespace, MemoryType type) {
        String path = namespace.toPath();
        return (int) records.values().stream()
            .filter(r -> matchesNamespace(r, path))
            .filter(r -> type == r.getType())
            .count();
    }

    private boolean matchesNamespace(MemoryRecord record, String namespacePath) {
        if (record.getNamespace() == null) return false;
        return Objects.equals(namespacePath, record.getNamespace().toPath());
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private record ScoredRecord(MemoryRecord record, double score) { }
}
