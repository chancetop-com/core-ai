package ai.core.memory;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.memory.extraction.MemoryCoordinator;
import ai.core.memory.extraction.MemoryExtractor;
import ai.core.memory.history.ChatHistoryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Memory system that extracts and stores memorable information from chat history.
 * Users provide their own chat history storage via ChatHistoryProvider,
 * and this class handles memory extraction and retrieval.
 *
 * @author xander
 */
public class Memory {
    private static final Logger LOGGER = LoggerFactory.getLogger(Memory.class);

    public static MemoryBuilder builder() {
        return new MemoryBuilder();
    }

    private final MemoryStore memoryStore;
    private final MemoryCoordinator coordinator;
    private final LLMProvider llmProvider;
    private final MemoryConfig config;

    public Memory(MemoryStore memoryStore,
                          ChatHistoryProvider historyProvider,
                          MemoryExtractor extractor,
                          LLMProvider llmProvider,
                          MemoryConfig config) {
        this.memoryStore = memoryStore;
        this.coordinator = new MemoryCoordinator(memoryStore, historyProvider, extractor, llmProvider, config);
        this.llmProvider = llmProvider;
        this.config = config;
    }

    /**
     * Extract memories from the chat history for a user.
     * Only processes messages that haven't been extracted yet.
     *
     * @param userId the user to extract from
     */
    public void extract(String userId) {
        coordinator.extractFromHistory(userId);
    }

    /**
     * Extract memories if the unprocessed message count reaches the threshold.
     *
     * @param userId the user to check and possibly extract from
     */
    public void extractIfNeeded(String userId) {
        coordinator.extractIfNeeded(userId);
    }

    /**
     * Wait for any ongoing extraction to complete.
     */
    public void waitForExtraction() {
        coordinator.waitForCompletion();
    }

    /**
     * Retrieve relevant memories based on a query.
     *
     * @param userId the user whose memories to search
     * @param query the query to search for
     * @param topK maximum number of memories to return
     * @return list of relevant memory records
     */
    public List<MemoryRecord> retrieve(String userId, String query, int topK) {
        List<Double> queryEmbedding = generateEmbedding(query);
        if (queryEmbedding == null) {
            return List.of();
        }
        return memoryStore.searchByVector(userId, queryEmbedding, topK);
    }

    /**
     * Retrieve relevant memories with default topK.
     *
     * @param userId the user whose memories to search
     * @param query the query to search for
     * @return list of relevant memory records
     */
    public List<MemoryRecord> retrieve(String userId, String query) {
        return retrieve(userId, query, config.getDefaultTopK());
    }

    /**
     * Format memories as context for LLM prompts.
     *
     * @param memories the memories to format
     * @return formatted string for use in prompts
     */
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

    /**
     * Check if extraction is currently in progress.
     *
     * @return true if extraction is running
     */
    public boolean isExtractionInProgress() {
        return coordinator.isExtractionInProgress();
    }

    /**
     * Get the last extracted message index for a user.
     *
     * @param userId the user identifier
     * @return last extracted index, or -1 if nothing extracted yet
     */
    public int getLastExtractedIndex(String userId) {
        return coordinator.getLastExtractedIndex(userId);
    }

    /**
     * Reset extraction state for a user (useful for re-processing).
     *
     * @param userId the user identifier
     */
    public void resetExtractionState(String userId) {
        coordinator.resetExtractionState(userId);
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

    public MemoryStore getStore() {
        return memoryStore;
    }

    public MemoryConfig getConfig() {
        return config;
    }
}
