package ai.core.memory.longterm.extraction;

import ai.core.document.Tokenizer;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
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
 * @author xander
 */
public class LongTermMemoryCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(LongTermMemoryCoordinator.class);

    private final MemoryStore store;
    private final MemoryExtractor extractor;
    private final LLMProvider llmProvider;
    private final LongTermMemoryConfig config;
    private final Executor executor;

    private final ReentrantLock bufferLock = new ReentrantLock();
    private final List<Message> buffer = new ArrayList<>();
    private final AtomicInteger bufferedTokenCount = new AtomicInteger(0);
    private final AtomicInteger currentTurnIndex = new AtomicInteger(0);
    private final AtomicInteger lastExtractedTurnIndex = new AtomicInteger(0);
    private volatile int pendingEndTurn = -1;

    private volatile CompletableFuture<Void> currentExtraction;

    private volatile MemoryScope scope;
    private volatile String sessionId;

    public LongTermMemoryCoordinator(MemoryStore store,
                                     MemoryExtractor extractor,
                                     LLMProvider llmProvider,
                                     LongTermMemoryConfig config) {
        this(store, extractor, llmProvider, config, ForkJoinPool.commonPool());
    }

    public LongTermMemoryCoordinator(MemoryStore store,
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
            pendingEndTurn = endTurn;

            // Don't clear buffer here - clear after successful extraction
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
        boolean success = false;
        try {
            List<MemoryRecord> records = extractor.extract(scope, messages);
            if (records.isEmpty()) {
                LOGGER.debug("No memories extracted from {} messages", messages.size());
                success = true;
                return;
            }

            for (MemoryRecord record : records) {
                record.setScope(scope);
                record.setSessionId(sessionId);
            }

            // Generate embeddings
            List<List<Double>> embeddings = generateEmbeddings(records);
            if (embeddings.size() != records.size()) {
                LOGGER.error("Embedding count mismatch: {} records, {} embeddings",
                    records.size(), embeddings.size());
                return;
            }

            store.saveAll(records, embeddings);
            success = true;

            LOGGER.info("Extracted and saved {} memories from turns {}-{}",
                records.size(), startTurn, endTurn);

        } catch (Exception e) {
            LOGGER.error("Failed to extract memories", e);
        } finally {
            if (success) {
                clearBufferAfterExtraction(endTurn);
            }
        }
    }

    private void clearBufferAfterExtraction(int extractedEndTurn) {
        bufferLock.lock();
        try {
            if (pendingEndTurn == extractedEndTurn) {
                buffer.clear();
                bufferedTokenCount.set(0);
                lastExtractedTurnIndex.set(extractedEndTurn);
                pendingEndTurn = -1;
            }
        } finally {
            bufferLock.unlock();
        }
    }

    private List<List<Double>> generateEmbeddings(List<MemoryRecord> records) {
        List<String> contents = records.stream()
            .map(MemoryRecord::getContent)
            .toList();

        try {
            EmbeddingResponse response = llmProvider.embeddings(new EmbeddingRequest(contents));

            List<List<Double>> embeddings = new ArrayList<>();
            if (response != null && response.embeddings != null) {
                for (var embeddingData : response.embeddings) {
                    if (embeddingData.embedding != null) {
                        embeddings.add(embeddingData.embedding.vectors());
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
