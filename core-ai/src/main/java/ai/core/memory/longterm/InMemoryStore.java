package ai.core.memory.longterm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * In-memory implementation of MemoryStore with vector and keyword search.
 *
 * @author xander
 */
public class InMemoryStore implements MemoryStore {

    private final Map<String, MemoryRecord> records = new ConcurrentHashMap<>();
    private final Map<String, float[]> embeddings = new ConcurrentHashMap<>();

    @Override
    public void save(MemoryRecord record, float[] embedding) {
        records.put(record.getId(), record);
        if (embedding != null) {
            embeddings.put(record.getId(), embedding);
        }
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
    public List<MemoryRecord> findByScope(MemoryScope scope) {
        return records.values().stream()
            .filter(r -> matchesScope(r, scope))
            .toList();
    }

    @Override
    public List<MemoryRecord> searchByVector(MemoryScope scope, float[] queryEmbedding, int topK) {
        return searchByVector(scope, queryEmbedding, topK, null);
    }

    @Override
    public List<MemoryRecord> searchByVector(MemoryScope scope, float[] queryEmbedding, int topK, SearchFilter filter) {
        List<ScoredRecord> scored = new ArrayList<>();

        for (MemoryRecord record : records.values()) {
            if (!matchesScope(record, scope)) continue;
            if (filter != null && !filter.matches(record)) continue;

            float[] embedding = embeddings.get(record.getId());
            if (embedding == null) continue;

            double similarity = cosineSimilarity(queryEmbedding, embedding);
            double effectiveScore = record.calculateEffectiveScore(similarity);
            scored.add(new ScoredRecord(record, effectiveScore));
        }

        return extractTopK(scored, topK);
    }

    @Override
    public List<MemoryRecord> searchByKeyword(MemoryScope scope, String keyword, int topK) {
        return searchByKeyword(scope, keyword, topK, null);
    }

    @Override
    public List<MemoryRecord> searchByKeyword(MemoryScope scope, String keyword, int topK, SearchFilter filter) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }

        String[] keywords = keyword.toLowerCase(java.util.Locale.ROOT).split("\\s+");
        List<ScoredRecord> scored = new ArrayList<>();

        for (MemoryRecord record : records.values()) {
            if (!matchesScope(record, scope)) continue;
            if (filter != null && !filter.matches(record)) continue;

            double keywordScore = calculateKeywordScore(record.getContent(), keywords);
            if (keywordScore > 0) {
                double effectiveScore = record.calculateEffectiveScore(keywordScore);
                scored.add(new ScoredRecord(record, effectiveScore));
            }
        }

        return extractTopK(scored, topK);
    }

    @Override
    public void delete(String id) {
        records.remove(id);
        embeddings.remove(id);
    }

    @Override
    public void deleteByScope(MemoryScope scope) {
        List<String> idsToDelete = records.values().stream()
            .filter(r -> matchesScope(r, scope))
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
    public List<MemoryRecord> findDecayed(MemoryScope scope, double threshold) {
        return records.values().stream()
            .filter(r -> matchesScope(r, scope))
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
    public int count(MemoryScope scope) {
        return (int) records.values().stream()
            .filter(r -> matchesScope(r, scope))
            .count();
    }

    @Override
    public int countByType(MemoryScope scope, MemoryType type) {
        return (int) records.values().stream()
            .filter(r -> matchesScope(r, scope))
            .filter(r -> type == r.getType())
            .count();
    }

    private boolean matchesScope(MemoryRecord record, MemoryScope queryScope) {
        if (queryScope == null) {
            return true;
        }
        return queryScope.matches(record.getScope());
    }

    private double calculateKeywordScore(String content, String[] keywords) {
        if (content == null || content.isEmpty() || keywords.length == 0) {
            return 0.0;
        }

        String lowerContent = content.toLowerCase(java.util.Locale.ROOT);
        int matchCount = 0;
        int totalWeight = 0;

        for (String kw : keywords) {
            if (kw.isEmpty()) continue;
            totalWeight++;

            // Exact word match (higher score)
            Pattern wordPattern = Pattern.compile("\\b" + Pattern.quote(kw) + "\\b");
            if (wordPattern.matcher(lowerContent).find()) {
                matchCount += 2;
            } else if (lowerContent.contains(kw)) {
                // Partial match (lower score)
                matchCount += 1;
            }
        }

        if (totalWeight == 0) return 0.0;
        return (double) matchCount / (totalWeight * 2);  // Normalize to 0-1
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

    private List<MemoryRecord> extractTopK(List<ScoredRecord> scored, int topK) {
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

    private record ScoredRecord(MemoryRecord record, double score) { }
}
