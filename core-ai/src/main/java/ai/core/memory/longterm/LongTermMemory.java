package ai.core.memory.longterm;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.Message;
import ai.core.memory.conflict.MemoryConflictResolver;
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

    private final MemoryStore store;
    private final LongTermMemoryCoordinator coordinator;
    private final LLMProvider llmProvider;
    private final LongTermMemoryConfig config;
    private MemoryScope currentScope;
    private String currentSessionId;

    public LongTermMemory(MemoryStore store,
                          MemoryExtractor extractor,
                          LLMProvider llmProvider,
                          LongTermMemoryConfig config) {
        this(store, extractor, llmProvider, config, null);
    }

    public LongTermMemory(MemoryStore store,
                          MemoryExtractor extractor,
                          LLMProvider llmProvider,
                          LongTermMemoryConfig config,
                          MemoryConflictResolver conflictResolver) {
        this.store = store;
        this.coordinator = new LongTermMemoryCoordinator(store, extractor, llmProvider, config, conflictResolver);
        this.llmProvider = llmProvider;
        this.config = config;
    }

    // ==================== Session Management ====================
    public void startSession(MemoryScope scope, String sessionId) {
        this.currentScope = scope;
        this.currentSessionId = sessionId;
        coordinator.initSession(scope, sessionId);
    }

    public void startSessionForUser(String userId, String sessionId) {
        startSession(MemoryScope.forUser(userId), sessionId);
    }

    public void onMessage(Message message) {
        coordinator.onMessage(message);
    }

    public void endSession() {
        coordinator.onSessionEnd();
    }

    // ==================== Memory Recall ====================
    public List<MemoryRecord> recall(String query, int topK) {
        if (currentScope == null) {
            return List.of();
        }
        return recall(currentScope, query, topK);
    }

    public List<MemoryRecord> recall(MemoryScope scope, String query, int topK) {
        float[] queryEmbedding = generateEmbedding(query);
        if (queryEmbedding == null) {
            return List.of();
        }
        return store.searchByVector(scope, queryEmbedding, topK);
    }

    public List<MemoryRecord> recall(String query, int topK, MemoryType... types) {
        if (currentScope == null || types == null || types.length == 0) {
            return recall(query, topK);
        }

        float[] queryEmbedding = generateEmbedding(query);
        if (queryEmbedding == null) {
            return List.of();
        }

        SearchFilter filter = SearchFilter.builder()
            .types(types)
            .build();
        return store.searchByVector(currentScope, queryEmbedding, topK, filter);
    }

    // ==================== Context Formatting ====================
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

    // ==================== Status Methods ====================
    public boolean hasMemories() {
        return currentScope != null && store.count(currentScope) > 0;
    }

    public int getMemoryCount() {
        return currentScope != null ? store.count(currentScope) : 0;
    }

    public void waitForExtraction() {
        coordinator.waitForCompletion();
    }

    public boolean isExtractionInProgress() {
        return coordinator.isExtractionInProgress();
    }

    // ==================== Internal Methods ====================

    private float[] generateEmbedding(String text) {
        if (llmProvider == null || text == null || text.isBlank()) {
            return null;
        }

        try {
            EmbeddingResponse response = llmProvider.embeddings(new EmbeddingRequest(List.of(text)));
            if (response != null && response.embeddings != null && !response.embeddings.isEmpty()) {
                var embeddingData = response.embeddings.getFirst();
                if (embeddingData.embedding != null) {
                    return embeddingData.embedding.toFloatArray();
                }
            }
            LOGGER.warn("Failed to generate embedding: empty or invalid response for query, textLength={}", text.length());
        } catch (Exception e) {
            LOGGER.warn("Failed to generate embedding for memory recall, textLength={}", text.length(), e);
        }
        return null;
    }

    // ==================== Getters ====================

    public MemoryScope getCurrentScope() {
        return currentScope;
    }

    public String getCurrentSessionId() {
        return currentSessionId;
    }

    public MemoryStore getStore() {
        return store;
    }

    public LongTermMemoryConfig getConfig() {
        return config;
    }
}
