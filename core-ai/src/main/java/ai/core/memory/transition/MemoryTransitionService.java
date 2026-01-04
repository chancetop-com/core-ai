package ai.core.memory.transition;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.Message;
import ai.core.memory.conflict.ConflictGroup;
import ai.core.memory.conflict.ConflictStrategy;
import ai.core.memory.conflict.MemoryConflictResolver;
import ai.core.memory.longterm.LongTermMemory;
import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.MemoryScope;
import ai.core.memory.longterm.MemoryStore;
import ai.core.memory.longterm.extraction.MemoryExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Service for managing memory transitions from short-term to long-term.
 *
 * <p>Handles:
 * - Extracting important information from conversations
 * - Detecting and resolving conflicts with existing memories
 * - Persisting new memories to long-term storage
 *
 * @author xander
 */
public class MemoryTransitionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryTransitionService.class);

    private final LongTermMemory longTermMemory;
    private final MemoryStore store;
    private final MemoryExtractor extractor;
    private final MemoryConflictResolver conflictResolver;
    private final LLMProvider llmProvider;
    private final ConflictStrategy defaultStrategy;

    public MemoryTransitionService(LongTermMemory longTermMemory,
                                   MemoryExtractor extractor,
                                   LLMProvider llmProvider) {
        this(longTermMemory, extractor, llmProvider, ConflictStrategy.NEWEST_WITH_MERGE);
    }

    public MemoryTransitionService(LongTermMemory longTermMemory,
                                   MemoryExtractor extractor,
                                   LLMProvider llmProvider,
                                   ConflictStrategy defaultStrategy) {
        this.longTermMemory = longTermMemory;
        this.store = longTermMemory.getStore();
        this.extractor = extractor;
        this.llmProvider = llmProvider;
        this.conflictResolver = new MemoryConflictResolver(llmProvider, null);
        this.defaultStrategy = defaultStrategy;
    }

    /**
     * Extract memories from messages and save to long-term storage.
     *
     * @param scope the scope to save under
     * @param messages  the conversation messages
     * @return list of saved memory records
     */
    public List<MemoryRecord> extractAndSave(MemoryScope scope, List<Message> messages) {
        if (scope == null || messages == null || messages.isEmpty()) {
            return List.of();
        }

        // Extract memories from conversation
        List<MemoryRecord> extracted = extractor.extract(scope, messages);
        if (extracted.isEmpty()) {
            LOGGER.debug("No memories extracted from {} messages", messages.size());
            return List.of();
        }

        LOGGER.info("Extracted {} memories from conversation", extracted.size());

        // Check for conflicts with existing memories
        List<MemoryRecord> toSave = resolveConflictsWithExisting(scope, extracted);

        // Generate embeddings and save
        return saveWithEmbeddings(toSave);
    }

    /**
     * Handle session end event - trigger memory extraction.
     *
     * @param scope       the user's scope
     * @param sessionMessages all messages from the session
     */
    public void onSessionEnd(MemoryScope scope, List<Message> sessionMessages) {
        if (scope == null || sessionMessages == null || sessionMessages.isEmpty()) {
            return;
        }

        LOGGER.info("Session ended, extracting memories for scope: {}", scope.toKey());

        try {
            List<MemoryRecord> saved = extractAndSave(scope, sessionMessages);
            LOGGER.info("Saved {} memories on session end", saved.size());
        } catch (Exception e) {
            LOGGER.error("Failed to extract memories on session end", e);
        }
    }

    /**
     * Check and resolve conflicts between new and existing memories.
     *
     * @param scope the scope
     * @param newRecords new memory records to check
     * @return resolved list of records to save
     */
    public List<MemoryRecord> resolveConflictsWithExisting(MemoryScope scope,
                                                           List<MemoryRecord> newRecords) {
        if (newRecords == null || newRecords.isEmpty()) {
            return List.of();
        }

        List<MemoryRecord> result = new ArrayList<>();

        for (MemoryRecord newRecord : newRecords) {
            // Find potentially conflicting existing memories
            List<MemoryRecord> existing = findSimilarExisting(scope, newRecord);

            if (existing.isEmpty()) {
                // No conflicts, add as-is
                result.add(newRecord);
            } else {
                // Resolve conflict
                List<MemoryRecord> allRecords = new ArrayList<>(existing);
                allRecords.add(newRecord);

                ConflictGroup group = new ConflictGroup(extractTopic(newRecord), allRecords);
                MemoryRecord resolved = conflictResolver.resolveGroup(group, defaultStrategy);
                addResolvedRecord(resolved, existing, result);
            }
        }

        return result;
    }

    /**
     * Add resolved record to result list if applicable.
     * Handles deletion of existing records when merging.
     */
    private void addResolvedRecord(MemoryRecord resolved, List<MemoryRecord> existing, List<MemoryRecord> result) {
        if (resolved == null || existing.contains(resolved)) {
            return;
        }
        result.add(resolved);
        if (shouldDeleteExistingOnMerge()) {
            deleteExisting(existing);
        }
    }

    private boolean shouldDeleteExistingOnMerge() {
        return defaultStrategy == ConflictStrategy.MERGE
            || defaultStrategy == ConflictStrategy.NEWEST_WITH_MERGE;
    }

    /**
     * Find existing memories that may conflict with the new one.
     *
     * @param scope the scope
     * @param newRecord the new record
     * @return list of potentially conflicting existing records
     */
    private List<MemoryRecord> findSimilarExisting(MemoryScope scope, MemoryRecord newRecord) {
        if (newRecord.getContent() == null || newRecord.getContent().isBlank()) {
            return List.of();
        }

        // Recall similar memories
        List<MemoryRecord> similar = longTermMemory.recall(scope, newRecord.getContent(), 3);

        // Filter to same type
        return similar.stream()
            .filter(r -> r.getType() == newRecord.getType())
            .filter(r -> conflictResolver.mayConflict(r, newRecord))
            .toList();
    }

    /**
     * Delete existing memory records (used after merge).
     *
     * @param records records to delete
     */
    private void deleteExisting(List<MemoryRecord> records) {
        for (MemoryRecord record : records) {
            try {
                store.delete(record.getId());
                LOGGER.debug("Deleted merged memory: {}", record.getId());
            } catch (Exception e) {
                LOGGER.warn("Failed to delete merged memory: {}", record.getId(), e);
            }
        }
    }

    /**
     * Save records with generated embeddings.
     *
     * @param records records to save
     * @return saved records
     */
    private List<MemoryRecord> saveWithEmbeddings(List<MemoryRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        List<MemoryRecord> saved = new ArrayList<>();

        for (MemoryRecord record : records) {
            float[] embedding = generateEmbedding(record.getContent());
            if (embedding != null) {
                store.save(record, embedding);
                saved.add(record);
            } else {
                LOGGER.warn("Failed to generate embedding for memory: {}", record.getId());
            }
        }

        return saved;
    }

    /**
     * Generate embedding for text.
     *
     * @param text the text to embed
     * @return embedding array, or null if failed
     */
    private float[] generateEmbedding(String text) {
        if (llmProvider == null || text == null || text.isBlank()) {
            return null;
        }

        try {
            var response = llmProvider.embeddings(new EmbeddingRequest(List.of(text)));
            if (response != null && response.embeddings != null && !response.embeddings.isEmpty()) {
                var embeddingData = response.embeddings.getFirst();
                if (embeddingData.embedding != null) {
                    return embeddingData.embedding.toFloatArray();
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to generate embedding", e);
        }
        return null;
    }

    /**
     * Extract topic from memory record.
     *
     * @param record the memory record
     * @return topic string
     */
    private String extractTopic(MemoryRecord record) {
        if (record == null || record.getContent() == null) {
            return "unknown";
        }

        String content = record.getContent();
        String[] words = content.split("\\s+");
        if (words.length >= 3) {
            return words[0] + " " + words[1] + " " + words[2];
        }
        return content.length() > 30 ? content.substring(0, 30) : content;
    }

    /**
     * Get the conflict resolver.
     *
     * @return the conflict resolver instance
     */
    public MemoryConflictResolver getConflictResolver() {
        return conflictResolver;
    }

    /**
     * Get the memory extractor.
     *
     * @return the extractor instance
     */
    public MemoryExtractor getExtractor() {
        return extractor;
    }
}
