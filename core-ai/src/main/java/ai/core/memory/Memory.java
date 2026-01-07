package ai.core.memory;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author xander
 */
public class Memory {
    private static final Logger LOGGER = LoggerFactory.getLogger(Memory.class);
    private static final int DEFAULT_TOP_K = 5;

    public static MemoryBuilder builder() {
        return new MemoryBuilder();
    }

    private final MemoryStore memoryStore;
    private final LLMProvider llmProvider;
    private final int defaultTopK;

    public Memory(MemoryStore memoryStore, LLMProvider llmProvider) {
        this(memoryStore, llmProvider, DEFAULT_TOP_K);
    }

    public Memory(MemoryStore memoryStore, LLMProvider llmProvider, int defaultTopK) {
        this.memoryStore = memoryStore;
        this.llmProvider = llmProvider;
        this.defaultTopK = defaultTopK;
    }

    public List<MemoryRecord> retrieve(String userId, String query, int topK) {
        List<Double> queryEmbedding = generateEmbedding(query);
        if (queryEmbedding == null) {
            return List.of();
        }
        return memoryStore.searchByVector(userId, queryEmbedding, topK);
    }

    public List<MemoryRecord> retrieve(String userId, String query) {
        return retrieve(userId, query, defaultTopK);
    }

    public String formatAsContext(List<MemoryRecord> memories) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder(128);
        sb.append("[User Memory]\n");
        for (MemoryRecord record : memories) {
            sb.append("- ").append(record.getContent()).append('\n');
        }
        return sb.toString();
    }

    public boolean hasMemories(String userId) {
        return memoryStore.count(userId) > 0;
    }

    public int getMemoryCount(String userId) {
        return memoryStore.count(userId);
    }

    public MemoryStore getStore() {
        return memoryStore;
    }

    private List<Double> generateEmbedding(String text) {
        if (llmProvider == null || text == null || text.isBlank()) {
            return null;
        }

        try {
            EmbeddingResponse response = llmProvider.embeddings(new EmbeddingRequest(List.of(text)));
            if (response != null && response.embeddings != null && !response.embeddings.isEmpty()) {
                var embeddingData = response.embeddings.getFirst();
                if (embeddingData.embedding != null) {
                    return embeddingData.embedding.vectors();
                }
            }
            LOGGER.warn("Failed to generate embedding: empty or invalid response, textLength={}", text.length());
        } catch (Exception e) {
            LOGGER.warn("Failed to generate embedding for memory retrieval, textLength={}", text.length(), e);
        }
        return null;
    }
}
