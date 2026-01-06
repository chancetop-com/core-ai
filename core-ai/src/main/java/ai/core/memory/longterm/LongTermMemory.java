package ai.core.memory.longterm;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.Message;
import ai.core.memory.history.ChatHistoryStore;
import ai.core.memory.longterm.extraction.LongTermMemoryCoordinator;
import ai.core.memory.longterm.extraction.MemoryExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author xander
 */
public class LongTermMemory {
    private static final Logger LOGGER = LoggerFactory.getLogger(LongTermMemory.class);

    public static LongTermMemoryBuilder builder() {
        return new LongTermMemoryBuilder();
    }

    private final MemoryStore memoryStore;
    private final LongTermMemoryCoordinator coordinator;
    private final LLMProvider llmProvider;
    private final LongTermMemoryConfig config;
    private String currentSessionId;

    public LongTermMemory(MemoryStore memoryStore,
                          ChatHistoryStore chatHistoryStore,
                          MemoryExtractor extractor,
                          LLMProvider llmProvider,
                          LongTermMemoryConfig config) {
        this.memoryStore = memoryStore;
        this.coordinator = new LongTermMemoryCoordinator(memoryStore, chatHistoryStore, extractor, llmProvider, config);
        this.llmProvider = llmProvider;
        this.config = config;
    }

    public void startSession(String sessionId) {
        this.currentSessionId = sessionId;
        coordinator.initSession(sessionId);
    }

    public void onMessage(Message message) {
        coordinator.onMessage(message);
    }

    public void endSession() {
        coordinator.onSessionEnd();
    }


    public List<MemoryRecord> recall(String query, int topK) {
        List<Double> queryEmbedding = generateEmbedding(query);
        if (queryEmbedding == null) {
            return List.of();
        }
        return memoryStore.searchByVector(queryEmbedding, topK);
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

    public boolean hasMemories() {
        return memoryStore.count() > 0;
    }

    public int getMemoryCount() {
        return memoryStore.count();
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
            LOGGER.warn("Failed to generate embedding for memory recall, textLength={}", text.length(), e);
        }
        return null;
    }

    public String getCurrentSessionId() {
        return currentSessionId;
    }

    public MemoryStore getStore() {
        return memoryStore;
    }

    public LongTermMemoryConfig getConfig() {
        return config;
    }
}
