package ai.core.memory;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Message;
import ai.core.memory.decay.MemoryDecayPolicy;
import ai.core.memory.extractor.MemoryConsolidator;
import ai.core.memory.extractor.MemoryExtractor;
import ai.core.memory.model.MemoryContext;
import ai.core.memory.model.MemoryEntry;
import ai.core.memory.model.MemoryFilter;
import ai.core.memory.model.MemoryType;
import ai.core.memory.model.RetrievalOptions;
import ai.core.memory.retriever.MemoryRetriever;
import ai.core.memory.store.HybridMemoryStore;
import ai.core.memory.store.InMemoryKVStore;
import ai.core.memory.store.InMemoryVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Central coordinator for long-term memory operations.
 * Manages memory extraction, consolidation, retrieval, and decay.
 *
 * @author xander
 */
public class MemoryManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryManager.class);

    /**
     * Create a MemoryManager with default in-memory stores.
     */
    public static MemoryManager createDefault(LLMProvider llmProvider, String model) {
        var memoryConfig = MemoryConfig.builder().build();
        return create(llmProvider, model, memoryConfig);
    }

    /**
     * Create a MemoryManager with the given configuration.
     */
    public static MemoryManager create(LLMProvider llmProvider, String model, MemoryConfig memoryConfig) {
        var vectorStore = memoryConfig.getVectorStore() != null
            ? memoryConfig.getVectorStore()
            : new InMemoryVectorStore();
        var kvStore = memoryConfig.getKvStore() != null
            ? memoryConfig.getKvStore()
            : new InMemoryKVStore();

        var hybridStore = new HybridMemoryStore(
            vectorStore,
            kvStore,
            memoryConfig.getGraphStore(),
            llmProvider,
            memoryConfig.getEmbeddingModel()
        );

        var memoryExtractor = new MemoryExtractor(llmProvider, model);
        var memoryConsolidator = new MemoryConsolidator(llmProvider, model, hybridStore);
        var memoryRetriever = new MemoryRetriever(hybridStore, llmProvider, memoryConfig.getEmbeddingModel());

        return new MemoryManager(hybridStore, memoryExtractor, memoryConsolidator, memoryRetriever, memoryConfig);
    }

    private final LongTermMemory longTermMemory;
    private final MemoryExtractor extractor;
    private final MemoryConsolidator consolidator;
    private final MemoryRetriever retriever;
    private final MemoryConfig config;
    private final Executor executor;

    public MemoryManager(LongTermMemory longTermMemory,
                         MemoryExtractor extractor,
                         MemoryConsolidator consolidator,
                         MemoryRetriever retriever,
                         MemoryConfig config) {
        this(longTermMemory, extractor, consolidator, retriever, config, ForkJoinPool.commonPool());
    }

    public MemoryManager(LongTermMemory longTermMemory,
                         MemoryExtractor extractor,
                         MemoryConsolidator consolidator,
                         MemoryRetriever retriever,
                         MemoryConfig config,
                         Executor executor) {
        this.longTermMemory = longTermMemory;
        this.extractor = extractor;
        this.consolidator = consolidator;
        this.retriever = retriever;
        this.config = config;
        this.executor = executor;
    }

    /**
     * Process conversation to extract and store memories (Two-Phase Pipeline).
     * Phase 1: Extract candidate memories from conversation
     * Phase 2: Consolidate (ADD/UPDATE/DELETE/NOOP) with existing memories
     *
     * @param messages conversation messages
     * @param userId   user identifier
     */
    public void processConversation(List<Message> messages, String userId) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        LOGGER.info("Processing conversation for memory extraction, userId={}, messages={}",
            userId, messages.size());

        // Phase 1: Extract candidate memories
        List<MemoryEntry> candidates = extractor.extract(messages, userId);
        if (candidates.isEmpty()) {
            LOGGER.debug("No memories extracted from conversation");
            return;
        }

        LOGGER.info("Extracted {} candidate memories", candidates.size());

        // Phase 2: Consolidate with existing memories
        for (MemoryEntry candidate : candidates) {
            try {
                var operation = consolidator.determineOperation(candidate);
                switch (operation.type()) {
                    case ADD -> {
                        longTermMemory.add(candidate);
                        LOGGER.debug("Added memory: {}", candidate.getContent());
                    }
                    case UPDATE -> {
                        longTermMemory.update(operation.existingId(), candidate);
                        LOGGER.debug("Updated memory: {} -> {}",
                            operation.existingId(), candidate.getContent());
                    }
                    case DELETE -> {
                        longTermMemory.delete(operation.existingId());
                        LOGGER.debug("Deleted memory: {}, reason: {}",
                            operation.existingId(), operation.reason());
                    }
                    case NOOP -> LOGGER.debug("Skipped memory: {}, reason: {}",
                        candidate.getContent(), operation.reason());
                    default -> LOGGER.warn("Unknown operation type: {}", operation.type());
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to process memory candidate: {}", candidate.getContent(), e);
            }
        }
    }

    /**
     * Process conversation asynchronously.
     */
    public CompletableFuture<Void> processConversationAsync(List<Message> messages, String userId) {
        return CompletableFuture.runAsync(() -> processConversation(messages, userId), executor);
    }

    /**
     * Retrieve relevant memories for the query (Hybrid Trigger).
     * Layer 1: Fast auto-retrieval based on trigger mode
     * Layer 2: Available via SearchMemoryTool for deep retrieval
     *
     * @param query   the user query
     * @param userId  user identifier
     * @param options retrieval options
     * @return memory context for prompt injection
     */
    public MemoryContext retrieve(String query, String userId, RetrievalOptions options) {
        RetrievalOptions opts = options;
        if (opts == null) {
            opts = RetrievalOptions.defaults();
        }

        // Check trigger mode
        if (!shouldRetrieve(query)) {
            return MemoryContext.empty();
        }

        // Apply timeout for fast mode
        if (opts.isFastMode()) {
            return retrieveWithTimeout(query, userId, opts, opts.getTimeout());
        }

        return doRetrieve(query, userId, opts);
    }

    /**
     * Retrieve memories for Layer 1 auto-retrieval.
     */
    public MemoryContext autoRetrieve(String query, String userId) {
        var options = RetrievalOptions.fast(
            config.getAutoRetrievalTopK(),
            config.getAutoRetrievalTimeout()
        ).withFilter(MemoryFilter.forUser(userId).withMinStrength(config.getMinStrength()));

        return retrieve(query, userId, options);
    }

    /**
     * Retrieve memories for Layer 2 tool-based deep retrieval.
     */
    public MemoryContext deepRetrieve(String query, String userId, MemoryFilter filter) {
        var options = RetrievalOptions.deep(config.getToolRetrievalTopK())
            .withGraphSearch(config.isToolRetrievalEnableGraph(), 2)
            .withFilter(filter != null ? filter : MemoryFilter.forUser(userId));

        return doRetrieve(query, userId, options);
    }

    /**
     * Apply memory decay based on configured policy.
     */
    public void applyDecay() {
        MemoryDecayPolicy policy = config.getDecayPolicy();
        if (policy == null) {
            return;
        }

        LOGGER.info("Applying memory decay...");
        longTermMemory.applyDecay(policy);

        // Remove memories below threshold
        int removed = longTermMemory.removeDecayedMemories(config.getMinStrength());
        if (removed > 0) {
            LOGGER.info("Removed {} decayed memories", removed);
        }
    }

    /**
     * Consolidate short-term memory to long-term memory at session end.
     */
    public void consolidateFromShortTerm(ShortTermMemory shortTermMemory, String userId) {
        if (shortTermMemory == null) {
            return;
        }

        String summary = shortTermMemory.getSummary();
        if (summary != null && !summary.isBlank()) {
            // Store session summary as episodic memory
            var entry = MemoryEntry.builder()
                .userId(userId)
                .content("Session summary: " + summary)
                .type(MemoryType.EPISODIC)
                .importance(0.5)
                .build();
            longTermMemory.add(entry);
            LOGGER.info("Consolidated session summary to long-term memory");
        }
    }

    /**
     * Get all memories for a user.
     */
    public List<MemoryEntry> getMemories(String userId, MemoryType type, int limit) {
        return longTermMemory.getByUserId(userId, type, limit);
    }

    /**
     * Add a memory directly.
     */
    public void addMemory(MemoryEntry entry) {
        longTermMemory.add(entry);
    }

    /**
     * Delete a memory by ID.
     */
    public void deleteMemory(String memoryId) {
        longTermMemory.delete(memoryId);
    }

    // Internal methods
    private boolean shouldRetrieve(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }

        return switch (config.getTriggerMode()) {
            case AUTO, HYBRID -> true;
            case TOOL_ONLY -> false;
            case CONDITIONAL -> matchesTriggerCondition(query);
        };
    }

    private boolean matchesTriggerCondition(String query) {
        // Short queries may need more context
        if (query.length() < config.getMinQueryLengthForSkip()) {
            return true;
        }

        // Check for trigger keywords
        String lower = query.toLowerCase(Locale.ROOT);
        for (String keyword : config.getTriggerKeywords()) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }

    private MemoryContext retrieveWithTimeout(String query, String userId,
                                               RetrievalOptions options, Duration timeout) {
        try {
            return CompletableFuture.supplyAsync(() -> doRetrieve(query, userId, options), executor)
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOGGER.warn("Memory retrieval timed out after {}ms", timeout.toMillis());
            return MemoryContext.empty();
        } catch (Exception e) {
            LOGGER.warn("Memory retrieval failed: {}", e.getMessage());
            return MemoryContext.empty();
        }
    }

    private MemoryContext doRetrieve(String query, String userId, RetrievalOptions options) {
        var filter = options.getFilter();
        if (filter == null) {
            filter = MemoryFilter.forUser(userId);
        }

        return retriever.retrieve(query, options.getTopK(), filter);
    }

    // Getters
    public LongTermMemory getLongTermMemory() {
        return longTermMemory;
    }

    public MemoryConfig getConfig() {
        return config;
    }
}
