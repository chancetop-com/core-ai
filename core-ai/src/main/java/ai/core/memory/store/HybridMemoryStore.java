package ai.core.memory.store;

import ai.core.document.Embedding;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.Message;
import ai.core.memory.LongTermMemory;
import ai.core.memory.decay.MemoryDecayPolicy;
import ai.core.memory.model.MemoryEntry;
import ai.core.memory.model.MemoryFilter;
import ai.core.memory.model.MemoryType;
import ai.core.memory.model.SemanticMemoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Hybrid memory store combining Vector Store and KV Store.
 * - Vector Store: semantic similarity search for all memories
 * - KV Store: fast exact-match lookup for semantic memories with known subjects
 *
 * @author xander
 */
public class HybridMemoryStore implements LongTermMemory {
    private static final Logger LOGGER = LoggerFactory.getLogger(HybridMemoryStore.class);

    private final VectorMemoryStore vectorStore;
    private final KeyValueMemoryStore kvStore;
    private final LLMProvider llmProvider;

    public HybridMemoryStore(VectorMemoryStore vectorStore,
                             KeyValueMemoryStore kvStore,
                             LLMProvider llmProvider) {
        this.vectorStore = vectorStore;
        this.kvStore = kvStore;
        this.llmProvider = llmProvider;
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    public HybridMemoryStore(VectorMemoryStore vectorStore,
                             KeyValueMemoryStore kvStore,
                             GraphMemoryStore graphStore,
                             LLMProvider llmProvider,
                             String embeddingModel) {
        this.vectorStore = vectorStore;
        this.kvStore = kvStore;
        this.llmProvider = llmProvider;
        // graphStore and embeddingModel are reserved for future use
    }

    // ========== add overloads ==========
    @Override
    public void add(MemoryEntry entry) {
        // Generate embedding if not present
        if (entry.getEmbedding() == null && entry.getContent() != null) {
            entry.setEmbedding(generateEmbedding(entry.getContent()));
        }

        // Save to vector store
        vectorStore.save(entry);

        // Save to KV store for semantic memories with subject
        if (entry instanceof SemanticMemoryEntry sem && sem.buildKvKey() != null) {
            kvStore.set(sem.buildKvKey(), entry);
        }

        LOGGER.debug("Added memory: id={}, type={}", entry.getId(), entry.getType());
    }

    @Override
    public void add(String content) {
        add(MemoryEntry.builder().content(content).type(MemoryType.SEMANTIC).build());
    }

    @Override
    public void add(Message message) {
        if (message != null && message.content != null) {
            add(message.content);
        }
    }

    @Override
    public void addBatch(List<MemoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }

        // Generate embeddings for entries without them
        var contentsToEmbed = new ArrayList<String>();
        var entriesToEmbed = new ArrayList<MemoryEntry>();
        for (var entry : entries) {
            if (entry.getEmbedding() == null && entry.getContent() != null) {
                contentsToEmbed.add(entry.getContent());
                entriesToEmbed.add(entry);
            }
        }

        if (!contentsToEmbed.isEmpty()) {
            var embeddings = generateEmbeddings(contentsToEmbed);
            for (int i = 0; i < entriesToEmbed.size() && i < embeddings.size(); i++) {
                entriesToEmbed.get(i).setEmbedding(embeddings.get(i));
            }
        }

        // Save to vector store
        vectorStore.saveBatch(entries);

        // Save to KV store for semantic memories
        for (var entry : entries) {
            if (entry instanceof SemanticMemoryEntry sem && sem.buildKvKey() != null) {
                kvStore.set(sem.buildKvKey(), entry);
            }
        }

        LOGGER.debug("Added {} memories in batch", entries.size());
    }

    @Override
    public void update(String memoryId, MemoryEntry entry) {
        // Get existing entry to find old KV key
        var existing = vectorStore.get(memoryId);

        // Regenerate embedding if content changed
        if (entry.getEmbedding() == null && entry.getContent() != null) {
            entry.setEmbedding(generateEmbedding(entry.getContent()));
        }

        // Update vector store
        vectorStore.update(memoryId, entry);

        // Update KV store
        if (existing.isPresent() && existing.get() instanceof SemanticMemoryEntry oldSem) {
            var oldKey = oldSem.buildKvKey();
            if (oldKey != null) {
                kvStore.delete(oldKey);
            }
        }
        if (entry instanceof SemanticMemoryEntry sem && sem.buildKvKey() != null) {
            kvStore.set(sem.buildKvKey(), entry);
        }

        LOGGER.debug("Updated memory: id={}", memoryId);
    }

    @Override
    public void updateMetadata(MemoryEntry entry) {
        vectorStore.update(entry.getId(), entry);
    }

    @Override
    public void delete(String memoryId) {
        var existing = vectorStore.get(memoryId);

        vectorStore.delete(memoryId);

        // Remove from KV store
        if (existing.isPresent() && existing.get() instanceof SemanticMemoryEntry sem) {
            var key = sem.buildKvKey();
            if (key != null) {
                kvStore.delete(key);
            }
        }

        LOGGER.debug("Deleted memory: id={}", memoryId);
    }

    @Override
    public void deleteBatch(List<String> memoryIds) {
        if (memoryIds != null) {
            memoryIds.forEach(this::delete);
        }
    }

    // ========== retrieve overloads ==========
    @Override
    public List<MemoryEntry> retrieve(String query, int topK, MemoryFilter filter) {
        if (query == null || query.isBlank()) {
            return vectorStore.findAll(filter, topK);
        }

        Set<MemoryEntry> results = new HashSet<>();

        // Vector similarity search
        var queryEmbedding = generateEmbedding(query);
        double threshold = filter != null && filter.getSimilarityThreshold() != null
            ? filter.getSimilarityThreshold()
            : 0.7;
        var vectorResults = vectorStore.similaritySearch(queryEmbedding, topK, threshold, filter);
        for (var scored : vectorResults) {
            results.add(scored.entry());
        }

        // KV exact-match lookup (extract potential subject from query)
        String subject = extractSubject(query);
        if (subject != null && filter != null && filter.getUserId() != null) {
            String key = filter.getUserId() + ":" + subject.toLowerCase(Locale.ROOT);
            kvStore.get(key).ifPresent(results::add);
        }

        // Deduplicate and return
        return new ArrayList<>(results).stream()
            .filter(e -> filter == null || filter.matches(e))
            .limit(topK)
            .toList();
    }

    @Override
    public List<String> retrieve(String query, int topK) {
        return retrieve(query, topK, null).stream()
            .map(MemoryEntry::getContent)
            .toList();
    }

    @Override
    public List<MemoryEntry> findSimilar(Embedding embedding, int topK, double threshold) {
        return vectorStore.similaritySearch(embedding, topK, threshold, null)
            .stream()
            .map(VectorMemoryStore.ScoredMemory::entry)
            .toList();
    }

    @Override
    public Optional<MemoryEntry> getById(String memoryId) {
        return vectorStore.get(memoryId);
    }

    @Override
    public List<MemoryEntry> getByUserId(String userId, MemoryType type, int limit) {
        var filter = MemoryFilter.forUser(userId);
        if (type != null) {
            filter.withTypes(type);
        }
        return vectorStore.findAll(filter, limit);
    }

    @Override
    public void applyDecay(MemoryDecayPolicy policy) {
        var now = Instant.now();
        var allMemories = vectorStore.findAll(null, Integer.MAX_VALUE);

        for (var entry : allMemories) {
            double newStrength = policy.calculateStrength(entry, now);
            entry.setStrength(newStrength);
            updateMetadata(entry);
        }

        LOGGER.info("Applied decay to {} memories", allMemories.size());
    }

    @Override
    public List<MemoryEntry> getDecayedMemories(double minStrength) {
        return vectorStore.findAll(null, Integer.MAX_VALUE).stream()
            .filter(e -> e.getStrength() < minStrength)
            .toList();
    }

    @Override
    public int removeDecayedMemories(double minStrength) {
        var decayed = getDecayedMemories(minStrength);
        var ids = decayed.stream().map(MemoryEntry::getId).toList();
        deleteBatch(ids);
        LOGGER.info("Removed {} decayed memories with strength < {}", ids.size(), minStrength);
        return ids.size();
    }

    @Override
    public String buildContext() {
        var memories = vectorStore.findAll(null, 10);
        if (memories.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder(256);
        sb.append("[Long-term Memory]\n");
        for (var memory : memories) {
            sb.append("- ").append(memory.getContent()).append('\n');
        }
        return sb.toString();
    }

    @Override
    public void clear() {
        if (vectorStore instanceof InMemoryVectorStore inMem) {
            inMem.clear();
        }
        kvStore.clear();
    }

    @Override
    public int size() {
        return vectorStore.count(null);
    }

    // Helper methods
    private Embedding generateEmbedding(String content) {
        if (llmProvider == null || content == null || content.isBlank()) {
            return null;
        }
        try {
            var request = new EmbeddingRequest(List.of(content));
            var response = llmProvider.embeddings(request);
            if (response != null && response.embeddings != null && !response.embeddings.isEmpty()) {
                return response.embeddings.getFirst().embedding;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to generate embedding: {}", e.getMessage());
        }
        return null;
    }

    private List<Embedding> generateEmbeddings(List<String> contents) {
        if (llmProvider == null || contents == null || contents.isEmpty()) {
            return List.of();
        }
        try {
            var request = new EmbeddingRequest(contents);
            var response = llmProvider.embeddings(request);
            if (response != null && response.embeddings != null) {
                return response.embeddings.stream()
                    .map(e -> e.embedding)
                    .toList();
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to generate embeddings: {}", e.getMessage());
        }
        return List.of();
    }

    private String extractSubject(String query) {
        // Simple extraction: look for common patterns
        // This is a basic implementation; can be enhanced with NLP
        if (query == null) {
            return null;
        }

        // Pattern: "about X", "my X", "the X"
        String[] patterns = {"about ", "my ", "the ", "user's "};
        String lower = query.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            int idx = lower.indexOf(pattern);
            if (idx >= 0) {
                String rest = query.substring(idx + pattern.length()).trim();
                // Take first word or phrase
                int space = rest.indexOf(' ');
                return space > 0 ? rest.substring(0, space) : rest;
            }
        }
        return null;
    }
}
