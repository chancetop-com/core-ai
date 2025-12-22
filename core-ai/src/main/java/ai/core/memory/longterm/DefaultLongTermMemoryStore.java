package ai.core.memory.longterm;

import ai.core.llm.domain.Message;
import ai.core.memory.longterm.store.InMemoryMetadataStore;
import ai.core.memory.longterm.store.InMemoryRawConversationStore;
import ai.core.memory.longterm.store.InMemoryVectorStore;
import ai.core.memory.longterm.store.MemoryMetadataStore;
import ai.core.memory.longterm.store.MemoryVectorStore;
import ai.core.memory.longterm.store.RawConversationStore;
import ai.core.memory.longterm.store.VectorSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Default implementation of LongTermMemoryStore.
 * Coordinates metadata store, vector store, and raw conversation store.
 *
 * @author xander
 */
public class DefaultLongTermMemoryStore implements LongTermMemoryStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultLongTermMemoryStore.class);
    private static final int RERANKING_MULTIPLIER = 2;
    private static final double DECAY_UPDATE_THRESHOLD = 0.001;

    private final MemoryMetadataStore metadataStore;
    private final MemoryVectorStore vectorStore;
    private final RawConversationStore rawConversationStore;
    private final LongTermMemoryConfig config;

    public DefaultLongTermMemoryStore(LongTermMemoryConfig config) {
        this.config = config;
        this.metadataStore = createMetadataStore(config);
        this.vectorStore = createVectorStore(config);
        this.rawConversationStore = createRawConversationStore(config);
    }

    public DefaultLongTermMemoryStore(MemoryMetadataStore metadataStore,
                                      MemoryVectorStore vectorStore,
                                      RawConversationStore rawConversationStore,
                                      LongTermMemoryConfig config) {
        this.metadataStore = metadataStore;
        this.vectorStore = vectorStore;
        this.rawConversationStore = rawConversationStore;
        this.config = config;
    }

    // ==================== Factory Methods ====================

    private MemoryMetadataStore createMetadataStore(LongTermMemoryConfig config) {
        return switch (config.getMetadataStoreType()) {
            case IN_MEMORY -> new InMemoryMetadataStore();
            case SQLITE, POSTGRESQL -> throw new UnsupportedOperationException(
                "SQL metadata store not implemented yet. Use IN_MEMORY for now.");
        };
    }

    private MemoryVectorStore createVectorStore(LongTermMemoryConfig config) {
        return switch (config.getVectorStoreType()) {
            case IN_MEMORY -> new InMemoryVectorStore();
            case HNSW_LOCAL, MILVUS -> throw new UnsupportedOperationException(
                "HNSW/Milvus vector store not implemented yet. Use IN_MEMORY for now.");
        };
    }

    private RawConversationStore createRawConversationStore(LongTermMemoryConfig config) {
        if (!config.isEnableRawStorage()) {
            return null;
        }
        return new InMemoryRawConversationStore();
    }

    // ==================== Basic CRUD ====================

    @Override
    public void save(MemoryRecord record, float[] embedding) {
        metadataStore.save(record);
        vectorStore.save(record.getId(), embedding);
        LOGGER.debug("Saved memory: id={}, type={}", record.getId(), record.getType());
    }

    @Override
    public void saveAll(List<MemoryRecord> records, List<float[]> embeddings) {
        if (records.size() != embeddings.size()) {
            throw new IllegalArgumentException("Records and embeddings must have same size");
        }

        metadataStore.saveAll(records);

        List<String> ids = records.stream().map(MemoryRecord::getId).toList();
        vectorStore.saveAll(ids, embeddings);

        LOGGER.debug("Saved {} memories", records.size());
    }

    @Override
    public Optional<MemoryRecord> findById(String id) {
        return metadataStore.findById(id);
    }

    @Override
    public void delete(String id) {
        metadataStore.delete(id);
        vectorStore.delete(id);
        LOGGER.debug("Deleted memory: id={}", id);
    }

    @Override
    public void deleteByNamespace(Namespace namespace) {
        String path = namespace.toPath();
        List<MemoryRecord> records = metadataStore.findByUserId(path);
        List<String> ids = records.stream().map(MemoryRecord::getId).toList();

        metadataStore.deleteByUserId(path);
        vectorStore.deleteAll(ids);

        if (rawConversationStore != null) {
            rawConversationStore.deleteByUserId(path);
        }

        LOGGER.info("Deleted {} memories for namespace: {}", ids.size(), path);
    }

    // ==================== Search ====================

    @Override
    public List<MemoryRecord> search(Namespace namespace, float[] queryEmbedding, int topK) {
        return search(namespace, queryEmbedding, topK, null);
    }

    @Override
    public List<MemoryRecord> search(Namespace namespace, float[] queryEmbedding, int topK, SearchFilter filter) {
        String path = namespace.toPath();
        // 1. Get candidate IDs for this namespace (with filter)
        List<MemoryRecord> candidates = metadataStore.findByUserIdWithFilter(path, filter);
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<String> candidateIds = candidates.stream().map(MemoryRecord::getId).toList();

        // 2. Vector search within candidates
        int searchK = Math.min(topK * RERANKING_MULTIPLIER, candidateIds.size());
        List<VectorSearchResult> vectorResults = vectorStore.search(
            queryEmbedding, searchK, candidateIds);

        // 3. Build ID to similarity map
        Map<String, Double> similarityMap = vectorResults.stream()
            .collect(Collectors.toMap(
                VectorSearchResult::id,
                VectorSearchResult::similarity));

        // 4. Calculate effective scores and rank
        List<MemoryRecord> results = candidates.stream()
            .filter(r -> similarityMap.containsKey(r.getId()))
            .sorted(Comparator.comparingDouble(
                (MemoryRecord r) -> r.calculateEffectiveScore(similarityMap.get(r.getId())))
                .reversed())
            .limit(topK)
            .toList();

        // 5. Record access (async could be better)
        if (!results.isEmpty()) {
            List<String> accessedIds = results.stream().map(MemoryRecord::getId).toList();
            metadataStore.recordAccess(accessedIds);
        }

        return results;
    }

    // ==================== Access Tracking ====================

    @Override
    public void recordAccess(List<String> ids) {
        metadataStore.recordAccess(ids);
    }

    // ==================== Decay Management ====================

    @Override
    public void updateDecay() {
        if (!config.isEnableDecay()) {
            return;
        }

        LOGGER.info("Updating decay factors for all memories");

        List<MemoryRecord> allRecords = metadataStore.findAll();

        int updated = 0;
        for (MemoryRecord record : allRecords) {
            double newDecay = DecayCalculator.calculate(record);
            if (Math.abs(newDecay - record.getDecayFactor()) > DECAY_UPDATE_THRESHOLD) {
                metadataStore.updateDecayFactor(record.getId(), newDecay);
                updated++;
            }
        }

        LOGGER.info("Updated decay for {} memories", updated);
    }

    @Override
    public List<MemoryRecord> getDecayedMemories(Namespace namespace, double threshold) {
        return metadataStore.findDecayed(namespace.toPath(), threshold);
    }

    @Override
    public int cleanupDecayed(double threshold) {
        List<MemoryRecord> decayed = metadataStore.findAllDecayed(threshold);
        List<String> ids = decayed.stream().map(MemoryRecord::getId).toList();

        for (String id : ids) {
            delete(id);
        }

        LOGGER.info("Cleaned up {} decayed memories (threshold={})", ids.size(), threshold);
        return ids.size();
    }

    // ==================== Raw Conversation Storage ====================

    @Override
    public void saveRawConversation(RawConversationRecord record) {
        if (rawConversationStore == null) {
            LOGGER.debug("Raw conversation storage is disabled");
            return;
        }
        rawConversationStore.save(record);
        LOGGER.debug("Saved raw conversation: sessionId={}", record.getSessionId());
    }

    @Override
    public Optional<RawConversationRecord> getRawConversation(String id) {
        if (rawConversationStore == null) {
            return Optional.empty();
        }
        return rawConversationStore.findById(id);
    }

    @Override
    public List<Message> getSourceConversation(MemoryRecord memory) {
        if (rawConversationStore == null || memory.getRawRecordId() == null) {
            return List.of();
        }

        Optional<RawConversationRecord> rawOpt = rawConversationStore.findById(memory.getRawRecordId());
        if (rawOpt.isEmpty()) {
            return List.of();
        }

        RawConversationRecord raw = rawOpt.get();
        Integer start = memory.getStartTurnIndex();
        Integer end = memory.getEndTurnIndex();

        if (start != null && end != null) {
            return raw.getMessagesInRange(start, end);
        }

        return raw.getMessages();
    }

    @Override
    public int cleanupExpiredRawConversations() {
        if (rawConversationStore == null) {
            return 0;
        }

        int deleted = rawConversationStore.deleteExpired();
        LOGGER.info("Cleaned up {} expired raw conversations", deleted);
        return deleted;
    }

    // ==================== Statistics ====================

    @Override
    public int count(Namespace namespace) {
        return metadataStore.count(namespace.toPath());
    }

    @Override
    public int countByType(Namespace namespace, MemoryType type) {
        return metadataStore.countByType(namespace.toPath(), type);
    }

    // ==================== Getters for testing ====================

    public MemoryMetadataStore getMetadataStore() {
        return metadataStore;
    }

    public MemoryVectorStore getVectorStore() {
        return vectorStore;
    }

    public RawConversationStore getRawConversationStore() {
        return rawConversationStore;
    }
}
