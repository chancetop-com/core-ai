package ai.core.memory.retriever;

import ai.core.document.Embedding;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.memory.LongTermMemory;
import ai.core.memory.model.MemoryContext;
import ai.core.memory.model.MemoryEntry;
import ai.core.memory.model.MemoryFilter;
import ai.core.memory.model.MemoryType;
import ai.core.memory.util.MemoryUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Memory retriever for hybrid retrieval (Vector + KV + Graph).
 * Supports both fast mode (Layer 1) and deep mode (Layer 2).
 *
 * @author xander
 */
public class MemoryRetriever {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryRetriever.class);
    private static final double DEFAULT_THRESHOLD = 0.7;

    private final LongTermMemory memoryStore;
    private final LLMProvider llmProvider;

    @SuppressWarnings("PMD.UnusedFormalParameter")
    public MemoryRetriever(LongTermMemory memoryStore, LLMProvider llmProvider, String embeddingModel) {
        this.memoryStore = memoryStore;
        this.llmProvider = llmProvider;
        // embeddingModel is configured via LLMProvider; kept for API compatibility
    }

    /**
     * Retrieve relevant memories for the query.
     *
     * @param query  the search query
     * @param topK   maximum results
     * @param filter filter criteria
     * @return memory context with categorized results
     */
    public MemoryContext retrieve(String query, int topK, MemoryFilter filter) {
        if (query == null || query.isBlank()) {
            return MemoryContext.empty();
        }

        Set<MemoryEntry> results = new HashSet<>();
        double threshold = filter != null && filter.getSimilarityThreshold() != null
            ? filter.getSimilarityThreshold()
            : DEFAULT_THRESHOLD;

        // 1. Vector similarity search
        try {
            var embedding = generateEmbedding(query);
            if (embedding != null) {
                var vectorResults = memoryStore.findSimilar(embedding, topK * 2, threshold);
                results.addAll(vectorResults);
            }
        } catch (Exception e) {
            LOGGER.warn("Vector search failed: {}", e.getMessage());
        }

        // 2. Text-based retrieval (uses KV store internally)
        try {
            var textResults = memoryStore.retrieve(query, topK, filter);
            results.addAll(textResults);
        } catch (Exception e) {
            LOGGER.warn("Text retrieval failed: {}", e.getMessage());
        }

        // 3. Filter, deduplicate, and rank
        var filtered = results.stream()
            .filter(e -> filter == null || filter.matches(e))
            .distinct()
            .toList();

        var ranked = rankMemories(filtered);

        // 4. Split by type and limit
        var semantic = new ArrayList<MemoryEntry>();
        var episodic = new ArrayList<MemoryEntry>();

        for (var entry : ranked) {
            if (entry.getType() == MemoryType.SEMANTIC && semantic.size() < topK) {
                semantic.add(entry);
            } else if (entry.getType() == MemoryType.EPISODIC && episodic.size() < topK) {
                episodic.add(entry);
            }
        }

        // 5. Update access metadata
        updateAccessMetadata(semantic);
        updateAccessMetadata(episodic);

        LOGGER.debug("Retrieved {} semantic and {} episodic memories for query: {}",
            semantic.size(), episodic.size(), MemoryUtils.truncate(query));

        return new MemoryContext(semantic, episodic);
    }

    /**
     * Retrieve only semantic memories.
     */
    public List<MemoryEntry> retrieveSemantic(String query, int topK, MemoryFilter filter) {
        var context = retrieve(query, topK,
            (filter != null ? filter : MemoryFilter.empty()).withTypes(MemoryType.SEMANTIC));
        return context.getSemanticMemories();
    }

    /**
     * Retrieve only episodic memories.
     */
    public List<MemoryEntry> retrieveEpisodic(String query, int topK, MemoryFilter filter) {
        var context = retrieve(query, topK,
            (filter != null ? filter : MemoryFilter.empty()).withTypes(MemoryType.EPISODIC));
        return context.getEpisodicMemories();
    }

    /**
     * Search by embedding directly.
     */
    public List<MemoryEntry> searchByEmbedding(Embedding embedding, int topK, double threshold) {
        return memoryStore.findSimilar(embedding, topK, threshold);
    }

    /**
     * Rank memories by relevance score.
     * Combines semantic similarity, importance, and recency.
     */
    private List<MemoryEntry> rankMemories(List<MemoryEntry> memories) {
        return memories.stream()
            .sorted(Comparator.comparingDouble(this::calculateScore).reversed())
            .toList();
    }

    private double calculateScore(MemoryEntry entry) {
        double score = 0.0;

        // Base importance
        score += entry.getImportance() * 0.3;

        // Memory strength (decay factor)
        score += entry.getStrength() * 0.3;

        // Access frequency (logarithmic)
        score += Math.log(1 + entry.getAccessCount()) / 10 * 0.2;

        // Recency (last 30 days gets boost)
        long daysSinceAccess = java.time.Duration.between(
            entry.getLastAccessedAt(),
            java.time.Instant.now()
        ).toDays();
        if (daysSinceAccess < 30) {
            score += (30 - daysSinceAccess) / 30.0 * 0.2;
        }

        return score;
    }

    private void updateAccessMetadata(List<MemoryEntry> entries) {
        for (var entry : entries) {
            entry.recordAccess();
            try {
                memoryStore.updateMetadata(entry);
            } catch (Exception e) {
                LOGGER.debug("Failed to update access metadata: {}", e.getMessage());
            }
        }
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

}
