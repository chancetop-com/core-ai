package ai.core.memory.longterm.extraction;

import ai.core.document.Tokenizer;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.memory.longterm.LongTermMemoryConfig;
import ai.core.memory.longterm.LongTermMemoryStore;
import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.Namespace;
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

    private final LongTermMemoryStore store;
    private final MemoryExtractor extractor;
    private final LLMProvider llmProvider;
    private final LongTermMemoryConfig config;
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
    private volatile Namespace namespace;
    private volatile String sessionId;

    public LongTermMemoryCoordinator(LongTermMemoryStore store,
                                     MemoryExtractor extractor,
                                     LLMProvider llmProvider,
                                     LongTermMemoryConfig config) {
        this(store, extractor, llmProvider, config, ForkJoinPool.commonPool());
    }

    public LongTermMemoryCoordinator(LongTermMemoryStore store,
                                     MemoryExtractor extractor,
                                     LLMProvider llmProvider,
                                     LongTermMemoryConfig config,
                                     Executor executor) {
        this.store = store;
        this.extractor = extractor;
        this.llmProvider = llmProvider;
        this.config = config;
        this.executor = executor;
    }

    /**
     * Initialize a session with namespace.
     *
     * @param namespace the namespace for this session
     * @param sessionId session identifier
     */
    public void initSession(Namespace namespace, String sessionId) {
        this.namespace = namespace;
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

    /**
     * Trigger batch extraction of buffered messages.
     */
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

    /**
     * Perform the actual extraction and storage.
     */
    private void performExtraction(List<Message> messages, int startTurn, int endTurn) {
        try {
            List<MemoryRecord> records = extractor.extract(namespace, messages);
            if (records.isEmpty()) {
                LOGGER.debug("No memories extracted from {} messages", messages.size());
                return;
            }

            for (MemoryRecord record : records) {
                record.setNamespace(namespace);
                record.setSessionId(sessionId);
            }

            List<float[]> embeddings = generateEmbeddings(records);
            if (embeddings.size() != records.size()) {
                LOGGER.error("Embedding count mismatch: {} records, {} embeddings",
                    records.size(), embeddings.size());
                return;
            }

            store.saveAll(records, embeddings);
            lastExtractedTurnIndex.set(endTurn);

            LOGGER.info("Extracted and saved {} memories from turns {}-{}",
                records.size(), startTurn, endTurn);

        } catch (Exception e) {
            LOGGER.error("Failed to extract memories", e);
        }
    }

    private List<float[]> generateEmbeddings(List<MemoryRecord> records) {
        List<String> contents = records.stream()
            .map(MemoryRecord::getContent)
            .toList();

        EmbeddingResponse response = llmProvider.embeddings(new EmbeddingRequest(contents));

        List<float[]> embeddings = new ArrayList<>();
        if (response != null && response.embeddings != null) {
            for (var embeddingData : response.embeddings) {
                if (embeddingData.embedding != null) {
                    embeddings.add(embeddingData.embedding.toFloatArray());
                }
            }
        }
        return embeddings;
    }

    private void addToBuffer(Message message) {
        bufferLock.lock();
        try {
            buffer.add(message);
        } finally {
            bufferLock.unlock();
        }

        if (message.content != null) {
            bufferedTokenCount.addAndGet(Tokenizer.tokenCount(message.content));
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
