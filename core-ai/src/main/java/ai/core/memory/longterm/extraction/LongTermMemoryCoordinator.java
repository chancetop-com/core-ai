package ai.core.memory.longterm.extraction;

import ai.core.document.Tokenizer;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.memory.conflict.ConflictGroup;
import ai.core.memory.conflict.ConflictStrategy;
import ai.core.memory.conflict.MemoryConflictResolver;
import ai.core.memory.longterm.LongTermMemoryConfig;
import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.MemoryScope;
import ai.core.memory.longterm.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates long-term memory extraction and storage.
 * Manages extraction triggers: batch and session-end.
 * <p>
 * Thread-safe: uses locks for buffer operations and CompletableFuture for async coordination.
 *
 * @author xander
 */
public class LongTermMemoryCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(LongTermMemoryCoordinator.class);
    private static final int CONFLICT_SEARCH_TOP_K = 5;

    private final MemoryStore store;
    private final MemoryExtractor extractor;
    private final LLMProvider llmProvider;
    private final LongTermMemoryConfig config;
    private final MemoryConflictResolver conflictResolver;
    private final Executor executor;

    // Buffer with lock for thread safety
    private final ReentrantLock bufferLock = new ReentrantLock();
    private final List<Message> buffer = new ArrayList<>();
    private final AtomicInteger bufferedTokenCount = new AtomicInteger(0);
    private final AtomicInteger currentTurnIndex = new AtomicInteger(0);
    private final AtomicInteger lastExtractedTurnIndex = new AtomicInteger(0);

    // Async extraction tracking
    private volatile CompletableFuture<Void> currentExtraction;

    // Session context
    private volatile MemoryScope scope;
    private volatile String sessionId;

    public LongTermMemoryCoordinator(MemoryStore store,
                                     MemoryExtractor extractor,
                                     LLMProvider llmProvider,
                                     LongTermMemoryConfig config) {
        this(store, extractor, llmProvider, config, null, ForkJoinPool.commonPool());
    }

    public LongTermMemoryCoordinator(MemoryStore store,
                                     MemoryExtractor extractor,
                                     LLMProvider llmProvider,
                                     LongTermMemoryConfig config,
                                     MemoryConflictResolver conflictResolver) {
        this(store, extractor, llmProvider, config, conflictResolver, ForkJoinPool.commonPool());
    }

    public LongTermMemoryCoordinator(MemoryStore store,
                                     MemoryExtractor extractor,
                                     LLMProvider llmProvider,
                                     LongTermMemoryConfig config,
                                     MemoryConflictResolver conflictResolver,
                                     Executor executor) {
        this.store = store;
        this.extractor = extractor;
        this.llmProvider = llmProvider;
        this.config = config;
        this.conflictResolver = conflictResolver;
        this.executor = executor;
    }

    public void initSession(MemoryScope scope, String sessionId) {
        this.scope = scope;
        this.sessionId = sessionId;

        bufferLock.lock();
        try {
            buffer.clear();
        } finally {
            bufferLock.unlock();
        }

        bufferedTokenCount.set(0);
        currentTurnIndex.set(0);
        lastExtractedTurnIndex.set(0);
    }

    /**
     * Process a new message. Checks trigger conditions and extracts if needed.
     */
    public void onMessage(Message message) {
        if (message == null || message.role == RoleType.SYSTEM) {
            return;
        }

        if (message.role == RoleType.USER) {
            currentTurnIndex.incrementAndGet();
        }

        addToBuffer(message);

        if (shouldTriggerBatch()) {
            triggerBatchExtraction();
        }
    }

    /**
     * Called when the session ends. Extracts remaining buffer.
     */
    public void onSessionEnd() {
        if (!config.isExtractOnSessionEnd()) {
            return;
        }

        int bufferSize = getBufferSize();
        if (bufferSize > 0) {
            LOGGER.info("Session ending, extracting remaining {} messages", bufferSize);
            triggerBatchExtraction();
            waitForCompletion();
        }
    }

    private void triggerBatchExtraction() {
        List<Message> toExtract;
        int startTurn;
        int endTurn;

        bufferLock.lock();
        try {
            if (buffer.isEmpty()) {
                return;
            }

            // Check if extraction already in progress
            CompletableFuture<Void> current = currentExtraction;
            if (current != null && !current.isDone()) {
                return;
            }

            toExtract = new ArrayList<>(buffer);
            startTurn = lastExtractedTurnIndex.get() + 1;
            endTurn = currentTurnIndex.get();

            buffer.clear();
            bufferedTokenCount.set(0);
        } finally {
            bufferLock.unlock();
        }

        LOGGER.info("Triggering batch extraction: {} messages, turns {}-{}",
            toExtract.size(), startTurn, endTurn);

        if (config.isAsyncExtraction()) {
            currentExtraction = CompletableFuture.runAsync(
                () -> performExtraction(toExtract, startTurn, endTurn), executor);
        } else {
            performExtraction(toExtract, startTurn, endTurn);
        }
    }

    private void performExtraction(List<Message> messages, int startTurn, int endTurn) {
        try {
            List<MemoryRecord> records = extractor.extract(scope, messages);
            if (records.isEmpty()) {
                LOGGER.debug("No memories extracted from {} messages", messages.size());
                return;
            }

            for (MemoryRecord record : records) {
                record.setScope(scope);
                record.setSessionId(sessionId);
            }

            // Resolve conflicts with existing memories if enabled
            List<MemoryRecord> finalRecords = resolveConflictsIfEnabled(records);
            if (finalRecords.isEmpty()) {
                LOGGER.debug("No records to save after conflict resolution");
                return;
            }

            List<float[]> embeddings = generateEmbeddings(finalRecords);
            if (embeddings.size() != finalRecords.size()) {
                LOGGER.error("Embedding count mismatch: {} records, {} embeddings",
                    finalRecords.size(), embeddings.size());
                return;
            }

            store.saveAll(finalRecords, embeddings);
            lastExtractedTurnIndex.set(endTurn);

            LOGGER.info("Extracted and saved {} memories from turns {}-{}",
                finalRecords.size(), startTurn, endTurn);

        } catch (Exception e) {
            LOGGER.error("Failed to extract memories", e);
        }
    }

    /**
     * Resolve conflicts between new records and existing memories.
     */
    private List<MemoryRecord> resolveConflictsIfEnabled(List<MemoryRecord> newRecords) {
        if (!config.isEnableConflictResolution() || conflictResolver == null) {
            return newRecords;
        }

        ConflictStrategy strategy = config.getConflictStrategy();
        List<MemoryRecord> resolved = new ArrayList<>();

        for (MemoryRecord newRecord : newRecords) {
            MemoryRecord result = resolveConflictForRecord(newRecord, strategy);
            if (result != null) {
                resolved.add(result);
            }
        }

        return resolved;
    }

    /**
     * Resolve conflict for a single new record against existing memories.
     */
    private MemoryRecord resolveConflictForRecord(MemoryRecord newRecord, ConflictStrategy strategy) {
        // Search for similar existing memories
        List<MemoryRecord> similar = findSimilarExisting(newRecord);
        if (similar.isEmpty()) {
            return newRecord;
        }

        // Filter to find actual conflicts
        List<MemoryRecord> conflicts = similar.stream()
            .filter(existing -> conflictResolver.mayConflict(newRecord, existing))
            .toList();

        if (conflicts.isEmpty()) {
            return newRecord;
        }

        LOGGER.debug("Found {} conflicts for new memory: {}", conflicts.size(),
            truncate(newRecord.getContent(), 50));

        // Create conflict group with new record and existing conflicts
        List<MemoryRecord> allConflicting = new ArrayList<>(conflicts);
        allConflicting.add(newRecord);
        String topic = extractSimpleTopic(newRecord.getContent());
        ConflictGroup group = new ConflictGroup(topic, allConflicting);

        // Resolve the conflict
        MemoryRecord merged = conflictResolver.resolveGroup(group, strategy);

        // Delete the old conflicting records from store
        for (MemoryRecord oldRecord : conflicts) {
            try {
                store.delete(oldRecord.getId());
                LOGGER.debug("Deleted conflicting memory: {}", oldRecord.getId());
            } catch (Exception e) {
                LOGGER.warn("Failed to delete conflicting memory: {}", oldRecord.getId(), e);
            }
        }

        return merged;
    }

    /**
     * Find existing memories similar to the given record.
     */
    private List<MemoryRecord> findSimilarExisting(MemoryRecord record) {
        if (scope == null || record.getContent() == null) {
            return List.of();
        }

        float[] embedding = generateSingleEmbedding(record.getContent());
        if (embedding == null) {
            return List.of();
        }

        return store.searchByVector(scope, embedding, CONFLICT_SEARCH_TOP_K);
    }

    private float[] generateSingleEmbedding(String text) {
        if (llmProvider == null || text == null || text.isBlank()) {
            return null;
        }
        EmbeddingResponse response = llmProvider.embeddings(new EmbeddingRequest(List.of(text)));
        if (response != null && response.embeddings != null && !response.embeddings.isEmpty()) {
            var embeddingData = response.embeddings.getFirst();
            if (embeddingData.embedding != null) {
                return embeddingData.embedding.toFloatArray();
            }
        }
        return null;
    }

    private String extractSimpleTopic(String content) {
        if (content == null || content.isBlank()) {
            return "unknown";
        }
        return content.length() > 30 ? content.substring(0, 30) : content;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private List<float[]> generateEmbeddings(List<MemoryRecord> records) {
        List<String> contents = records.stream()
            .map(MemoryRecord::getContent)
            .toList();

        try {
            EmbeddingResponse response = llmProvider.embeddings(new EmbeddingRequest(contents));

            List<float[]> embeddings = new ArrayList<>();
            if (response != null && response.embeddings != null) {
                for (var embeddingData : response.embeddings) {
                    if (embeddingData.embedding != null) {
                        embeddings.add(embeddingData.embedding.toFloatArray());
                    }
                }
            }

            if (embeddings.size() != records.size()) {
                LOGGER.warn("Embedding generation returned incomplete results: expected={}, got={}",
                    records.size(), embeddings.size());
            }
            return embeddings;
        } catch (Exception e) {
            LOGGER.error("Failed to generate embeddings for {} memory records", records.size(), e);
            return List.of();
        }
    }

    private void addToBuffer(Message message) {
        bufferLock.lock();
        try {
            buffer.add(message);
            if (message.content != null) {
                bufferedTokenCount.addAndGet(Tokenizer.tokenCount(message.content));
            }
        } finally {
            bufferLock.unlock();
        }
    }

    private boolean shouldTriggerBatch() {
        CompletableFuture<Void> current = currentExtraction;
        if (current != null && !current.isDone()) {
            return false;
        }

        int messageCount = getBufferSize();
        int tokenCount = bufferedTokenCount.get();

        return messageCount >= config.getMaxBufferTurns()
            || tokenCount >= config.getMaxBufferTokens();
    }

    /**
     * Wait for any in-progress extraction to complete.
     */
    public void waitForCompletion() {
        CompletableFuture<Void> future = currentExtraction;
        if (future == null) {
            return;
        }

        try {
            Duration timeout = config.getExtractionTimeout();
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOGGER.warn("Extraction timed out after {}", config.getExtractionTimeout());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Extraction wait interrupted");
        } catch (ExecutionException e) {
            LOGGER.error("Extraction failed", e.getCause());
        }
    }

    // Getters for testing/monitoring

    public int getBufferSize() {
        bufferLock.lock();
        try {
            return buffer.size();
        } finally {
            bufferLock.unlock();
        }
    }

    public int getBufferedTokenCount() {
        return bufferedTokenCount.get();
    }

    public int getCurrentTurnIndex() {
        return currentTurnIndex.get();
    }

    public int getLastExtractedTurnIndex() {
        return lastExtractedTurnIndex.get();
    }

    public boolean isExtractionInProgress() {
        CompletableFuture<Void> current = currentExtraction;
        return current != null && !current.isDone();
    }
}
