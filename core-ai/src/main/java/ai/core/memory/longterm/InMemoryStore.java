package ai.core.memory.longterm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * In-memory implementation of MemoryStore with user-level isolation.
 *
 * @author xander
 */
public class InMemoryStore implements MemoryStore {

    private final Map<String, Map<String, MemoryRecord>> userRecords = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<Double>>> userEmbeddings = new ConcurrentHashMap<>();

    @Override
    public void save(String userId, MemoryRecord record) {
        getRecordsForUser(userId).put(record.getId(), record);
    }

    @Override
    public void save(String userId, MemoryRecord record, List<Double> embedding) {
        save(userId, record);
        if (embedding != null) {
            getEmbeddingsForUser(userId).put(record.getId(), embedding);
        }
    }

    @Override
    public void saveAll(String userId, List<MemoryRecord> recordList, List<List<Double>> embeddingList) {
        if (recordList.size() != embeddingList.size()) {
            throw new IllegalArgumentException("Records and embeddings must have same size");
        }
        for (int i = 0; i < recordList.size(); i++) {
            save(userId, recordList.get(i), embeddingList.get(i));
        }
    }

    @Override
    public Optional<MemoryRecord> findById(String userId, String id) {
        return Optional.ofNullable(getRecordsForUser(userId).get(id));
    }

    @Override
    public List<MemoryRecord> findAll(String userId) {
        return new ArrayList<>(getRecordsForUser(userId).values());
    }

    @Override
    public List<MemoryRecord> searchByVector(String userId, List<Double> queryEmbedding, int topK) {
        return searchByVector(userId, queryEmbedding, topK, null);
    }

    @Override
    public List<MemoryRecord> searchByVector(String userId, List<Double> queryEmbedding, int topK, SearchFilter filter) {
        List<ScoredRecord> scored = new ArrayList<>();
        Map<String, MemoryRecord> records = getRecordsForUser(userId);
        Map<String, List<Double>> embeddings = getEmbeddingsForUser(userId);

        for (MemoryRecord record : records.values()) {
            if (filter != null && !filter.matches(record)) continue;

            List<Double> embedding = embeddings.get(record.getId());
            if (embedding == null) continue;

            double similarity = cosineSimilarity(queryEmbedding, embedding);
            double effectiveScore = record.calculateEffectiveScore(similarity);
            scored.add(new ScoredRecord(record, effectiveScore));
        }

        return extractTopK(userId, scored, topK);
    }

    @Override
    public List<MemoryRecord> searchByKeyword(String userId, String keyword, int topK) {
        return searchByKeyword(userId, keyword, topK, null);
    }

    @Override
    public List<MemoryRecord> searchByKeyword(String userId, String keyword, int topK, SearchFilter filter) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }

        String[] keywords = keyword.toLowerCase(java.util.Locale.ROOT).split("\\s+");
        List<ScoredRecord> scored = new ArrayList<>();
        Map<String, MemoryRecord> records = getRecordsForUser(userId);

        for (MemoryRecord record : records.values()) {
            if (filter != null && !filter.matches(record)) continue;

            double keywordScore = calculateKeywordScore(record.getContent(), keywords);
            if (keywordScore > 0) {
                double effectiveScore = record.calculateEffectiveScore(keywordScore);
                scored.add(new ScoredRecord(record, effectiveScore));
            }
        }

        return extractTopK(userId, scored, topK);
    }

    @Override
    public void delete(String userId, String id) {
        getRecordsForUser(userId).remove(id);
        getEmbeddingsForUser(userId).remove(id);
    }

    @Override
    public void deleteAll(String userId) {
        userRecords.remove(userId);
        userEmbeddings.remove(userId);
    }

    @Override
    public void recordAccess(String userId, List<String> ids) {
        Map<String, MemoryRecord> records = getRecordsForUser(userId);
        for (String id : ids) {
            MemoryRecord record = records.get(id);
            if (record != null) {
                record.incrementAccessCount();
            }
        }
    }

    @Override
    public void updateDecayFactor(String userId, String id, double decayFactor) {
        MemoryRecord record = getRecordsForUser(userId).get(id);
        if (record != null) {
            record.setDecayFactor(decayFactor);
        }
    }

    @Override
    public List<MemoryRecord> findDecayed(String userId, double threshold) {
        return getRecordsForUser(userId).values().stream()
            .filter(r -> r.getDecayFactor() < threshold)
            .toList();
    }

    @Override
    public int deleteDecayed(String userId, double threshold) {
        List<String> idsToDelete = getRecordsForUser(userId).values().stream()
            .filter(r -> r.getDecayFactor() < threshold)
            .map(MemoryRecord::getId)
            .toList();
        idsToDelete.forEach(id -> delete(userId, id));
        return idsToDelete.size();
    }

    @Override
    public int count(String userId) {
        return getRecordsForUser(userId).size();
    }

    private Map<String, MemoryRecord> getRecordsForUser(String userId) {
        return userRecords.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
    }

    private Map<String, List<Double>> getEmbeddingsForUser(String userId) {
        return userEmbeddings.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
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

            Pattern wordPattern = Pattern.compile("\\b" + Pattern.quote(kw) + "\\b");
            if (wordPattern.matcher(lowerContent).find()) {
                matchCount += 2;
            } else if (lowerContent.contains(kw)) {
                matchCount += 1;
            }
        }

        if (totalWeight == 0) return 0.0;
        return (double) matchCount / (totalWeight * 2);
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.size() != b.size()) return 0.0;

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.size(); i++) {
            double valA = a.get(i);
            double valB = b.get(i);
            dotProduct += valA * valB;
            normA += valA * valA;
            normB += valB * valB;
        }

        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private List<MemoryRecord> extractTopK(String userId, List<ScoredRecord> scored, int topK) {
        List<MemoryRecord> results = scored.stream()
            .sorted(Comparator.comparingDouble(ScoredRecord::score).reversed())
            .limit(topK)
            .map(ScoredRecord::record)
            .toList();

        if (!results.isEmpty()) {
            recordAccess(userId, results.stream().map(MemoryRecord::getId).toList());
        }

        return results;
    }

    private record ScoredRecord(MemoryRecord record, double score) { }
}
