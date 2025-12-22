package ai.core.memory.longterm;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.Message;
import ai.core.memory.longterm.extraction.LongTermMemoryCoordinator;
import ai.core.memory.longterm.extraction.MemoryExtractor;

import java.util.List;
import java.util.Map;

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
 * <p>Usage example with namespace:
 * <pre>{@code
 * LongTermMemory memory = LongTermMemory.builder()
 *     .llmProvider(llmProvider)
 *     .namespaceTemplate(NamespaceTemplate.of("{org_id}", "{user_id}"))
 *     .build();
 *
 * // Start session with context variables
 * memory.startSession(Map.of("org_id", "acme", "user_id", "john"), "session-123");
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
    private final NamespaceTemplate namespaceTemplate;

    private Namespace currentNamespace;
    private String currentSessionId;

    public LongTermMemory(LongTermMemoryStore store,
                          MemoryExtractor extractor,
                          LLMProvider llmProvider,
                          LongTermMemoryConfig config) {
        this(store, extractor, llmProvider, config, NamespaceTemplate.USER_SCOPED);
    }

    public LongTermMemory(LongTermMemoryStore store,
                          MemoryExtractor extractor,
                          LLMProvider llmProvider,
                          LongTermMemoryConfig config,
                          NamespaceTemplate namespaceTemplate) {
        this.store = store;
        this.coordinator = new LongTermMemoryCoordinator(store, extractor, llmProvider, config);
        this.llmProvider = llmProvider;
        this.config = config;
        this.namespaceTemplate = namespaceTemplate != null ? namespaceTemplate : NamespaceTemplate.USER_SCOPED;
    }

    // ==================== Session Management ====================

    /**
     * Start a new session with namespace variables.
     * The namespace is resolved from the template using provided variables.
     *
     * @param namespaceVars variables for namespace resolution (e.g., user_id, org_id)
     * @param sessionId     session identifier
     */
    public void startSession(Map<String, String> namespaceVars, String sessionId) {
        this.currentNamespace = namespaceTemplate.resolve(namespaceVars);
        this.currentSessionId = sessionId;
        coordinator.initSession(currentNamespace, sessionId);
    }

    /**
     * Start a new session with explicit namespace.
     *
     * @param namespace the namespace for this session
     * @param sessionId session identifier
     */
    public void startSession(Namespace namespace, String sessionId) {
        this.currentNamespace = namespace;
        this.currentSessionId = sessionId;
        coordinator.initSession(namespace, sessionId);
    }

    /**
     * Start a new session with userId and sessionId.
     *
     * @param userId    user identifier
     * @param sessionId session identifier
     * @deprecated Use {@link #startSession(Namespace, String)} or {@link #startSessionForUser(String, String)}
     */
    @Deprecated
    public void startSession(String userId, String sessionId) {
        startSessionForUser(userId, sessionId);
    }

    /**
     * Start a user-scoped session (convenience method).
     *
     * @param userId    user identifier
     * @param sessionId session identifier
     */
    public void startSessionForUser(String userId, String sessionId) {
        startSession(Namespace.forUser(userId), sessionId);
    }

    /**
     * Process a message during agent execution.
     * Buffers the message for extraction.
     *
     * @param message the message to process
     */
    public void onMessage(Message message) {
        coordinator.onMessage(message);
    }

    /**
     * End the current session. Triggers extraction of remaining buffer.
     */
    public void endSession() {
        coordinator.onSessionEnd();
    }

    // ==================== Memory Recall ====================

    /**
     * Recall relevant memories for a query in current namespace.
     *
     * @param query the query to search for
     * @param topK  maximum number of memories to return
     * @return list of relevant memories
     */
    public List<MemoryRecord> recall(String query, int topK) {
        if (currentNamespace == null) {
            return List.of();
        }
        return recall(currentNamespace, query, topK);
    }

    /**
     * Recall relevant memories from a specific namespace.
     *
     * @param namespace the namespace to search
     * @param query     the query to search for
     * @param topK      maximum number of memories to return
     * @return list of relevant memories
     */
    public List<MemoryRecord> recall(Namespace namespace, String query, int topK) {
        float[] queryEmbedding = generateEmbedding(query);
        if (queryEmbedding == null) {
            return List.of();
        }
        return store.search(namespace, queryEmbedding, topK);
    }

    /**
     * Recall memories with type filter.
     *
     * @param query the query to search for
     * @param topK  maximum number of memories to return
     * @param types memory types to include
     * @return list of relevant memories
     */
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

    /**
     * Recall relevant memories for a specific user.
     *
     * @deprecated Use {@link #recall(Namespace, String, int)} instead
     */
    @Deprecated
    public List<MemoryRecord> recall(String userId, String query, int topK) {
        return recall(Namespace.forUser(userId), query, topK);
    }

    // ==================== Context Formatting ====================

    /**
     * Format recalled memories as context string for prompt injection.
     *
     * @param memories list of memories
     * @return formatted string for prompt context
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

    // ==================== Status Methods ====================

    /**
     * Check if long-term memory has any records for current namespace.
     *
     * @return true if memories exist
     */
    public boolean hasMemories() {
        return currentNamespace != null && store.count(currentNamespace) > 0;
    }

    /**
     * Get memory count for current namespace.
     *
     * @return number of memories
     */
    public int getMemoryCount() {
        return currentNamespace != null ? store.count(currentNamespace) : 0;
    }

    /**
     * Wait for any pending extraction to complete.
     */
    public void waitForExtraction() {
        coordinator.waitForCompletion();
    }

    /**
     * Check if extraction is in progress.
     *
     * @return true if extraction is running
     */
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

    /**
     * Get current user ID (convenience method).
     *
     * @deprecated Use {@link #getCurrentNamespace()} instead
     */
    @Deprecated
    public String getCurrentUserId() {
        return currentNamespace != null ? currentNamespace.getLast() : null;
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

    public NamespaceTemplate getNamespaceTemplate() {
        return namespaceTemplate;
    }
}
