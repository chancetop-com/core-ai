package ai.core.memory.longterm;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.Message;
import ai.core.memory.longterm.extraction.LongTermMemoryCoordinator;
import ai.core.memory.longterm.extraction.MemoryExtractor;

import java.util.List;

/**
 * Long-term memory facade for Agent integration.
 * Provides a simplified interface for agents to use long-term memory.
 *
 * <p>This class uses namespaces for flexible memory organization:
 * <ul>
 *   <li>User-scoped: memories isolated per user</li>
 *   <li>Session-scoped: memories isolated per session</li>
 *   <li>Organization-scoped: memories shared within an organization</li>
 *   <li>Global: memories shared across all users</li>
 * </ul>
 *
 * <p>Usage example:
 * <pre>{@code
 * LongTermMemory memory = LongTermMemory.builder()
 *     .llmProvider(llmProvider)
 *     .build();
 *
 * // Start session with namespace
 * memory.startSession(Namespace.of("acme", "john"), "session-123");
 *
 * // Or use simple user-scoped namespace
 * memory.startSessionForUser("user-123", "session-456");
 *
 * // Recall relevant memories
 * List<MemoryRecord> relevant = memory.recall("programming preferences", 5);
 * String context = memory.formatAsContext(relevant);
 *
 * memory.endSession();
 * }</pre>
 *
 * @author xander
 */
public class LongTermMemory {

    public static LongTermMemoryBuilder builder() {
        return new LongTermMemoryBuilder();
    }

    private final LongTermMemoryStore store;
    private final LongTermMemoryCoordinator coordinator;
    private final LLMProvider llmProvider;
    private final LongTermMemoryConfig config;
    private Namespace currentNamespace;
    private String currentSessionId;

    public LongTermMemory(LongTermMemoryStore store,
                          MemoryExtractor extractor,
                          LLMProvider llmProvider,
                          LongTermMemoryConfig config) {
        this.store = store;
        this.coordinator = new LongTermMemoryCoordinator(store, extractor, llmProvider, config);
        this.llmProvider = llmProvider;
        this.config = config;
    }

    // ==================== Session Management ====================
    public void startSession(Namespace namespace, String sessionId) {
        this.currentNamespace = namespace;
        this.currentSessionId = sessionId;
        coordinator.initSession(namespace, sessionId);
    }

    public void startSessionForUser(String userId, String sessionId) {
        startSession(Namespace.forUser(userId), sessionId);
    }

    public void onMessage(Message message) {
        coordinator.onMessage(message);
    }

    public void endSession() {
        coordinator.onSessionEnd();
    }

    // ==================== Memory Recall ====================
    public List<MemoryRecord> recall(String query, int topK) {
        if (currentNamespace == null) {
            return List.of();
        }
        return recall(currentNamespace, query, topK);
    }

    public List<MemoryRecord> recall(Namespace namespace, String query, int topK) {
        float[] queryEmbedding = generateEmbedding(query);
        if (queryEmbedding == null) {
            return List.of();
        }
        return store.search(namespace, queryEmbedding, topK);
    }

    public List<MemoryRecord> recall(String query, int topK, MemoryType... types) {
        if (currentNamespace == null || types == null || types.length == 0) {
            return recall(query, topK);
        }

        float[] queryEmbedding = generateEmbedding(query);
        if (queryEmbedding == null) {
            return List.of();
        }

        SearchFilter filter = SearchFilter.builder()
            .types(types)
            .build();
        return store.search(currentNamespace, queryEmbedding, topK, filter);
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
        return currentNamespace != null && store.count(currentNamespace) > 0;
    }

    public int getMemoryCount() {
        return currentNamespace != null ? store.count(currentNamespace) : 0;
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

        var response = llmProvider.embeddings(new EmbeddingRequest(List.of(text)));
        if (response != null && response.embeddings != null && !response.embeddings.isEmpty()) {
            var embeddingData = response.embeddings.getFirst();
            if (embeddingData.embedding != null) {
                return embeddingData.embedding.toFloatArray();
            }
        }
        return null;
    }

    // ==================== Getters ====================

    public Namespace getCurrentNamespace() {
        return currentNamespace;
    }

    public String getCurrentSessionId() {
        return currentSessionId;
    }

    public LongTermMemoryStore getStore() {
        return store;
    }

    public LongTermMemoryConfig getConfig() {
        return config;
    }
}
