package ai.core.memory.extraction;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.RoleType;
import ai.core.memory.MemoryConfig;
import ai.core.memory.MemoryRecord;
import ai.core.memory.MemoryStore;
import ai.core.memory.history.ChatHistoryProvider;
import ai.core.memory.history.ChatRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Coordinates memory extraction from chat history.
 * Tracks extraction state internally and extracts from user-provided history.
 *
 * @author xander
 */
public class MemoryCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryCoordinator.class);

    private final MemoryStore memoryStore;
    private final ChatHistoryProvider historyProvider;
    private final MemoryExtractor extractor;
    private final LLMProvider llmProvider;
    private final MemoryConfig config;
    private final Executor executor;

    // Track last extracted message index per user
    private final Map<String, Integer> extractedIndexMap = new ConcurrentHashMap<>();
    private volatile CompletableFuture<Void> currentExtraction;

    public MemoryCoordinator(MemoryStore memoryStore,
                                     ChatHistoryProvider historyProvider,
                                     MemoryExtractor extractor,
                                     LLMProvider llmProvider,
                                     MemoryConfig config) {
        this(memoryStore, historyProvider, extractor, llmProvider, config, ForkJoinPool.commonPool());
    }

    public MemoryCoordinator(MemoryStore memoryStore,
                                     ChatHistoryProvider historyProvider,
                                     MemoryExtractor extractor,
                                     LLMProvider llmProvider,
                                     MemoryConfig config,
                                     Executor executor) {
        this.memoryStore = memoryStore;
        this.historyProvider = historyProvider;
        this.extractor = extractor;
        this.llmProvider = llmProvider;
        this.config = config;
        this.executor = executor;
    }

    /**
     * Extract memories from unprocessed messages in the user's history.
     * This is the main entry point for triggering extraction.
     *
     * @param userId the user to extract from
     */
    public void extractFromHistory(String userId) {
        List<ChatRecord> unextracted = loadUnextracted(userId);
        if (unextracted.isEmpty()) {
            LOGGER.debug("No unextracted messages for user: {}", userId);
            return;
        }

        int totalCount = historyProvider.count(userId);
        LOGGER.info("Triggering extraction: {} unextracted messages", unextracted.size());

        if (config.isAsyncExtraction()) {
            currentExtraction = CompletableFuture.runAsync(
                () -> performExtraction(userId, unextracted, totalCount - 1), executor);
        } else {
            performExtraction(userId, unextracted, totalCount - 1);
        }
    }

    /**
     * Check if extraction should be triggered based on buffer size.
     *
     * @param userId the user to check
     * @return true if extraction threshold is reached
     */
    public boolean shouldExtract(String userId) {
        if (isExtractionInProgress()) {
            return false;
        }

        List<ChatRecord> unextracted = loadUnextracted(userId);
        int turnCount = countUserTurns(unextracted);
        return turnCount >= config.getMaxBufferTurns();
    }

    /**
     * Extract if threshold is reached, otherwise do nothing.
     *
     * @param userId the user to check and possibly extract from
     */
    public void extractIfNeeded(String userId) {
        if (shouldExtract(userId)) {
            extractFromHistory(userId);
        }
    }

    private List<ChatRecord> loadUnextracted(String userId) {
        List<ChatRecord> all = historyProvider.load(userId);
        int lastExtracted = extractedIndexMap.getOrDefault(userId, -1);

        if (lastExtracted < 0) {
            return filterNonSystemRecords(all);
        }

        if (lastExtracted >= all.size() - 1) {
            return List.of();
        }

        return filterNonSystemRecords(all.subList(lastExtracted + 1, all.size()));
    }

    private List<ChatRecord> filterNonSystemRecords(List<ChatRecord> records) {
        return records.stream()
            .filter(r -> r.role() != RoleType.SYSTEM)
            .toList();
    }

    private void performExtraction(String userId, List<ChatRecord> records, int lastMessageIndex) {
        boolean success = false;
        try {
            List<MemoryRecord> memoryRecords = extractor.extract(records);
            if (memoryRecords.isEmpty()) {
                LOGGER.debug("No memories extracted from {} messages", records.size());
                success = true;
                return;
            }

            List<List<Double>> embeddings = generateEmbeddings(memoryRecords);
            if (embeddings.size() != memoryRecords.size()) {
                LOGGER.error("Embedding count mismatch: {} records, {} embeddings",
                    memoryRecords.size(), embeddings.size());
                return;
            }

            memoryStore.saveAll(userId, memoryRecords, embeddings);
            success = true;

            LOGGER.info("Extracted and saved {} memories from {} messages",
                memoryRecords.size(), records.size());

        } catch (Exception e) {
            LOGGER.error("Failed to extract memories", e);
        } finally {
            if (success) {
                extractedIndexMap.put(userId, lastMessageIndex);
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

    private int countUserTurns(List<ChatRecord> records) {
        int count = 0;
        for (ChatRecord record : records) {
            if (record.role() == RoleType.USER) {
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

    /**
     * Get the last extracted message index for a user.
     *
     * @param userId the user identifier
     * @return last extracted index, or -1 if nothing extracted yet
     */
    public int getLastExtractedIndex(String userId) {
        return extractedIndexMap.getOrDefault(userId, -1);
    }

    /**
     * Reset extraction state for a user.
     *
     * @param userId the user identifier
     */
    public void resetExtractionState(String userId) {
        extractedIndexMap.remove(userId);
    }
}
