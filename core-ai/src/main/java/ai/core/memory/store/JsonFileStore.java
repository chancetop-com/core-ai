package ai.core.memory.store;

import ai.core.document.Embedding;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.memory.LongTermMemory;
import ai.core.memory.model.MemoryEntry;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * JSON file-based implementation of LongTermMemory.
 * Persists memories to a JSON file for durability across restarts.
 *
 * @author xander
 */
public class JsonFileStore implements LongTermMemory {
    private static final Logger LOGGER = LoggerFactory.getLogger(JsonFileStore.class);
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.5;
    private static final String DEFAULT_FILENAME = "memories.json";

    private static Path getDefaultPath() {
        String home = System.getProperty("user.home");
        return Path.of(home, ".core-ai", DEFAULT_FILENAME);
    }

    private final Path storagePath;
    private final Map<String, MemoryEntry> memories = new ConcurrentHashMap<>();
    private final LLMProvider llmProvider;
    private final ReadWriteLock fileLock = new ReentrantReadWriteLock();
    private final boolean autoSave;

    /**
     * Create a JsonFileStore with default path (~/.core-ai/memories.json).
     */
    public JsonFileStore() {
        this(getDefaultPath(), null, true);
    }

    /**
     * Create a JsonFileStore with custom path.
     */
    public JsonFileStore(Path storagePath) {
        this(storagePath, null, true);
    }

    /**
     * Create a JsonFileStore with LLM provider for embeddings.
     */
    public JsonFileStore(LLMProvider llmProvider) {
        this(getDefaultPath(), llmProvider, true);
    }

    /**
     * Create a JsonFileStore with full configuration.
     */
    public JsonFileStore(Path storagePath, LLMProvider llmProvider, boolean autoSave) {
        this.storagePath = storagePath;
        this.llmProvider = llmProvider;
        this.autoSave = autoSave;
        loadFromFile();
    }

    @Override
    public void add(MemoryEntry entry) {
        if (entry == null || entry.getContent() == null) {
            return;
        }

        if (llmProvider != null && entry.getEmbedding() == null) {
            entry.setEmbedding(generateEmbedding(entry.getContent()));
        }

        memories.put(entry.getId(), entry);
        LOGGER.debug("Added memory: {}", entry.getId());

        if (autoSave) {
            saveToFile();
        }
    }

    @Override
    public void update(String memoryId, MemoryEntry entry) {
        if (memoryId == null || entry == null) {
            return;
        }

        if (llmProvider != null && entry.getEmbedding() == null) {
            entry.setEmbedding(generateEmbedding(entry.getContent()));
        }

        entry.setId(memoryId);
        memories.put(memoryId, entry);
        LOGGER.debug("Updated memory: {}", memoryId);

        if (autoSave) {
            saveToFile();
        }
    }

    @Override
    public void delete(String memoryId) {
        if (memoryId != null) {
            memories.remove(memoryId);
            LOGGER.debug("Deleted memory: {}", memoryId);

            if (autoSave) {
                saveToFile();
            }
        }
    }

    @Override
    public Optional<MemoryEntry> getById(String memoryId) {
        return Optional.ofNullable(memories.get(memoryId));
    }

    @Override
    public List<MemoryEntry> getByUserId(String userId, int limit) {
        return memories.values().stream()
            .filter(e -> userId == null || userId.equals(e.getUserId()))
            .sorted(Comparator.comparing(MemoryEntry::getCreatedAt).reversed())
            .limit(limit)
            .toList();
    }

    @Override
    public List<MemoryEntry> search(String query, String userId, int topK) {
        if (query == null || query.isBlank()) {
            return getByUserId(userId, topK);
        }

        if (llmProvider != null) {
            var queryEmbedding = generateEmbedding(query);
            if (queryEmbedding != null) {
                return findSimilar(queryEmbedding, userId, topK, DEFAULT_SIMILARITY_THRESHOLD);
            }
        }

        String lowerQuery = query.toLowerCase(java.util.Locale.ROOT);
        return memories.values().stream()
            .filter(e -> userId == null || userId.equals(e.getUserId()))
            .filter(e -> e.getContent() != null && e.getContent().toLowerCase(java.util.Locale.ROOT).contains(lowerQuery))
            .sorted(Comparator.comparing(MemoryEntry::getCreatedAt).reversed())
            .limit(topK)
            .toList();
    }

    @Override
    public List<MemoryEntry> findSimilar(Embedding embedding, String userId, int topK, double threshold) {
        if (embedding == null || embedding.vectors() == null) {
            return List.of();
        }

        List<ScoredEntry> scored = new ArrayList<>();
        for (var entry : memories.values()) {
            if (userId != null && !userId.equals(entry.getUserId())) {
                continue;
            }
            if (entry.getEmbedding() == null || entry.getEmbedding().vectors() == null) {
                continue;
            }

            double similarity = cosineSimilarity(embedding.vectors(), entry.getEmbedding().vectors());
            if (similarity >= threshold) {
                scored.add(new ScoredEntry(entry, similarity));
            }
        }

        return scored.stream()
            .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
            .limit(topK)
            .map(ScoredEntry::entry)
            .toList();
    }

    @Override
    public List<MemoryEntry> getAll() {
        return new ArrayList<>(memories.values());
    }

    @Override
    public int size() {
        return memories.size();
    }

    @Override
    public void clear() {
        memories.clear();
        LOGGER.debug("Cleared all memories");

        if (autoSave) {
            saveToFile();
        }
    }

    /**
     * Manually save memories to file.
     */
    public void save() {
        saveToFile();
    }

    /**
     * Reload memories from file.
     */
    public void reload() {
        loadFromFile();
    }

    private void loadFromFile() {
        fileLock.readLock().lock();
        try {
            if (!Files.exists(storagePath)) {
                LOGGER.debug("No existing memory file at {}", storagePath);
                return;
            }

            String content = Files.readString(storagePath);
            if (content.isBlank()) {
                return;
            }

            var data = JSON.fromJSON(StorageData.class, content);
            if (data.memories != null) {
                memories.clear();
                for (var entry : data.memories) {
                    if (entry != null && entry.getId() != null) {
                        memories.put(entry.getId(), entry);
                    }
                }
                LOGGER.info("Loaded {} memories from {}", memories.size(), storagePath);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to load memories from file: {}", e.getMessage());
        } finally {
            fileLock.readLock().unlock();
        }
    }

    private void saveToFile() {
        fileLock.writeLock().lock();
        try {
            Files.createDirectories(storagePath.getParent());

            var data = new StorageData();
            data.version = "1.0";
            data.savedAt = Instant.now().toString();
            data.memories = new ArrayList<>(memories.values());

            String content = JSON.toJSON(data);
            Files.writeString(storagePath, content);
            LOGGER.debug("Saved {} memories to {}", memories.size(), storagePath);
        } catch (IOException e) {
            LOGGER.error("Failed to save memories to file: {}", e.getMessage());
        } finally {
            fileLock.writeLock().unlock();
        }
    }

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

    private double cosineSimilarity(List<Double> v1, List<Double> v2) {
        if (v1.size() != v2.size()) {
            return 0.0;
        }

        int size = v1.size();
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < size; i++) {
            double val1 = v1.get(i);
            double val2 = v2.get(i);
            dotProduct += val1 * val2;
            norm1 += val1 * val1;
            norm2 += val2 * val2;
        }

        if (norm1 == 0 || norm2 == 0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    private record ScoredEntry(MemoryEntry entry, double score) { }

    /**
     * Storage format for JSON file.
     */
    public static class StorageData {
        public String version;
        public String savedAt;
        public List<MemoryEntry> memories;
    }
}
