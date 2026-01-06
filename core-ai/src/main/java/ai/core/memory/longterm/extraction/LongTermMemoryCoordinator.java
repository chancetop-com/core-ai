package ai.core.memory.longterm.extraction;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.memory.history.ChatHistoryStore;
import ai.core.memory.longterm.LongTermMemoryConfig;
import ai.core.memory.longterm.MemoryRecord;
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

/**
 * @author xander
 */
public class LongTermMemoryCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(LongTermMemoryCoordinator.class);

    private final MemoryStore memoryStore;
    private final ChatHistoryStore chatHistoryStore;
    private final MemoryExtractor extractor;
    private final LLMProvider llmProvider;
    private final LongTermMemoryConfig config;
    private final Executor executor;

    private final AtomicInteger currentTurnIndex = new AtomicInteger(0);
    private volatile CompletableFuture<Void> currentExtraction;
    private volatile String sessionId;

    public LongTermMemoryCoordinator(MemoryStore memoryStore,
                                     ChatHistoryStore chatHistoryStore,
                                     MemoryExtractor extractor,
                                     LLMProvider llmProvider,
                                     LongTermMemoryConfig config) {
        this(memoryStore, chatHistoryStore, extractor, llmProvider, config, ForkJoinPool.commonPool());
    }

    public LongTermMemoryCoordinator(MemoryStore memoryStore,
                                     ChatHistoryStore chatHistoryStore,
                                     MemoryExtractor extractor,
                                     LLMProvider llmProvider,
                                     LongTermMemoryConfig config,
                                     Executor executor) {
        this.memoryStore = memoryStore;
        this.chatHistoryStore = chatHistoryStore;
        this.extractor = extractor;
        this.llmProvider = llmProvider;
        this.config = config;
        this.executor = executor;
    }

    public void initSession(String sessionId) {
        this.sessionId = sessionId;
        this.currentTurnIndex.set(0);
    }

    public void onMessage(Message message) {
        if (message == null || message.role == RoleType.SYSTEM) {
            return;
        }

        if (message.role == RoleType.USER) {
            currentTurnIndex.incrementAndGet();
        }

        chatHistoryStore.save(sessionId, message);

        if (shouldTriggerExtraction()) {
            triggerExtraction();
        }
    }

    public void onSessionEnd() {
        if (!config.isExtractOnSessionEnd()) {
            return;
        }

        List<Message> unextracted = chatHistoryStore.loadUnextracted(sessionId);
        if (!unextracted.isEmpty()) {
            LOGGER.info("Session ending, extracting remaining {} messages", unextracted.size());
            triggerExtraction();
            waitForCompletion();
        }
    }

    private void triggerExtraction() {
        CompletableFuture<Void> current = currentExtraction;
        if (current != null && !current.isDone()) {
            return;
        }

        List<Message> toExtract = chatHistoryStore.loadUnextracted(sessionId);
        if (toExtract.isEmpty()) {
            return;
        }

        int messageCount = toExtract.size();
        int currentIndex = chatHistoryStore.count(sessionId) - 1;

        LOGGER.info("Triggering extraction: {} messages", messageCount);

        if (config.isAsyncExtraction()) {
            currentExtraction = CompletableFuture.runAsync(
                () -> performExtraction(toExtract, currentIndex), executor);
        } else {
            performExtraction(toExtract, currentIndex);
        }
    }

    private void performExtraction(List<Message> messages, int lastMessageIndex) {
        boolean success = false;
        try {
            List<MemoryRecord> records = extractor.extract(messages);
            if (records.isEmpty()) {
                LOGGER.debug("No memories extracted from {} messages", messages.size());
                success = true;
                return;
            }

            List<List<Double>> embeddings = generateEmbeddings(records);
            if (embeddings.size() != records.size()) {
                LOGGER.error("Embedding count mismatch: {} records, {} embeddings",
                    records.size(), embeddings.size());
                return;
            }

            memoryStore.saveAll(records, embeddings);
            success = true;

            LOGGER.info("Extracted and saved {} memories from {} messages",
                records.size(), messages.size());

        } catch (Exception e) {
            LOGGER.error("Failed to extract memories", e);
        } finally {
            if (success) {
                chatHistoryStore.markExtracted(sessionId, lastMessageIndex);
            }
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

    private boolean shouldTriggerExtraction() {
        CompletableFuture<Void> current = currentExtraction;
        if (current != null && !current.isDone()) {
            return false;
        }

        List<Message> unextracted = chatHistoryStore.loadUnextracted(sessionId);
        int turnCount = countUserTurns(unextracted);

        return turnCount >= config.getMaxBufferTurns();
    }

    private int countUserTurns(List<Message> messages) {
        int count = 0;
        for (Message msg : messages) {
            if (msg.role == RoleType.USER) {
                count++;
            }
        }
        return count;
    }

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

    public boolean isExtractionInProgress() {
        CompletableFuture<Void> current = currentExtraction;
        return current != null && !current.isDone();
    }
}
