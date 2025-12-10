package ai.core.memory.store;

import ai.core.document.Embedding;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.memory.LongTermMemory;
import ai.core.memory.model.MemoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory implementation of LongTermMemory.
 * Supports basic CRUD operations and vector similarity search.
 *
 * @author xander
 */
public class InMemoryStore implements LongTermMemory {
    private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryStore.class);
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.5;
    private static final int DEFAULT_MAX_SIZE = 10000;

    private final Map<String, MemoryEntry> memories = new ConcurrentHashMap<>();
    private final LLMProvider llmProvider;
    private final int maxSize;

    public InMemoryStore() {
        this(null, DEFAULT_MAX_SIZE);
    }

    public InMemoryStore(LLMProvider llmProvider) {
        this(llmProvider, DEFAULT_MAX_SIZE);
    }

    public InMemoryStore(LLMProvider llmProvider, int maxSize) {
        this.llmProvider = llmProvider;
        this.maxSize = maxSize;
    }

    @Override
    public void add(MemoryEntry entry) {
        if (entry == null || entry.getContent() == null) {
            return;
        }

        // Evict oldest entries if at capacity
        if (memories.size() >= maxSize) {
            evictOldest();
        }

        // Generate embedding if llmProvider is available
        if (llmProvider != null && entry.getEmbedding() == null) {
            entry.setEmbedding(generateEmbedding(entry.getContent()));
        }

        memories.put(entry.getId(), entry);
        LOGGER.debug("Added memory: {}", entry.getId());
    }

    private void evictOldest() {
        memories.values().stream()
            .min(Comparator.comparing(MemoryEntry::getCreatedAt))
            .ifPresent(oldest -> {
                memories.remove(oldest.getId());
                LOGGER.debug("Evicted oldest memory: {}", oldest.getId());
            });
    }

    @Override
    public void update(String memoryId, MemoryEntry entry) {
        if (memoryId == null || entry == null) {
            return;
        }

        // Generate embedding if needed
        if (llmProvider != null && entry.getEmbedding() == null) {
            entry.setEmbedding(generateEmbedding(entry.getContent()));
        }

        entry.setId(memoryId);
        memories.put(memoryId, entry);
        LOGGER.debug("Updated memory: {}", memoryId);
    }

    @Override
    public void delete(String memoryId) {
        if (memoryId != null) {
            memories.remove(memoryId);
            LOGGER.debug("Deleted memory: {}", memoryId);
        }
    }

    @Override
    public Optional<MemoryEntry> getById(String memoryId) {
        return Optional.ofNullable(memories.get(memoryId));
    }

    @Override
    public List<MemoryEntry> getByUserId(String userId, int limit) {
        return memories.values().stream()
            .filter(e -> userId == null || userId.equals(e.getUserId()))
            .sorted(Comparator.comparing(MemoryEntry::getCreatedAt).reversed())
            .limit(limit)
            .toList();
    }

    @Override
    public List<MemoryEntry> search(String query, String userId, int topK) {
        if (query == null || query.isBlank()) {
            return getByUserId(userId, topK);
        }

        // If we have embedding capability, use vector search
        if (llmProvider != null) {
            var queryEmbedding = generateEmbedding(query);
            if (queryEmbedding != null) {
                return findSimilar(queryEmbedding, userId, topK, DEFAULT_SIMILARITY_THRESHOLD);
            }
        }

        // Fallback to simple keyword matching
        String lowerQuery = query.toLowerCase();
        return memories.values().stream()
            .filter(e -> userId == null || userId.equals(e.getUserId()))
            .filter(e -> e.getContent() != null && e.getContent().toLowerCase().contains(lowerQuery))
            .sorted(Comparator.comparing(MemoryEntry::getCreatedAt).reversed())
            .limit(topK)
            .toList();
    }

    @Override
    public List<MemoryEntry> findSimilar(Embedding embedding, String userId, int topK, double threshold) {
        if (embedding == null || embedding.vectors() == null) {
            return List.of();
        }

        List<ScoredEntry> scored = new ArrayList<>();
        for (var entry : memories.values()) {
            if (userId != null && !userId.equals(entry.getUserId())) {
                continue;
            }
            if (entry.getEmbedding() == null || entry.getEmbedding().vectors() == null) {
                continue;
            }

            double similarity = cosineSimilarity(embedding.vectors(), entry.getEmbedding().vectors());
            if (similarity >= threshold) {
                scored.add(new ScoredEntry(entry, similarity));
            }
        }

        return scored.stream()
            .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
            .limit(topK)
            .map(ScoredEntry::entry)
            .toList();
    }

    @Override
    public List<MemoryEntry> getAll() {
        return new ArrayList<>(memories.values());
    }

    @Override
    public int size() {
        return memories.size();
    }

    @Override
    public void clear() {
        memories.clear();
        LOGGER.debug("Cleared all memories");
    }

    private Embedding generateEmbedding(String content) {
        if (llmProvider == null || content == null || content.isBlank()) {
            return null;
        }
        try {
            var request = new EmbeddingRequest(List.of(content));
            var response = llmProvider.embeddings(request);
            if (response != null && response.embeddings != null && !response.embeddings.isEmpty()) {
                return response.embeddings.getFirst().embedding;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to generate embedding: {}", e.getMessage());
        }
        return null;
    }

    private double cosineSimilarity(List<Double> v1, List<Double> v2) {
        if (v1.size() != v2.size()) {
            return 0.0;
        }

        // Convert to primitive arrays for better performance
        int size = v1.size();
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < size; i++) {
            double val1 = v1.get(i);
            double val2 = v2.get(i);
            dotProduct += val1 * val2;
            norm1 += val1 * val1;
            norm2 += val2 * val2;
        }

        if (norm1 == 0 || norm2 == 0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    private record ScoredEntry(MemoryEntry entry, double score) {}
}
