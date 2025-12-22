package ai.core.memory.conflict;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.MemoryType;
import ai.core.memory.longterm.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves conflicts between memory records.
 *
 * <p>Supports multiple conflict resolution strategies:
 * - NEWEST_WINS: Keep the most recent record
 * - MERGE: Use LLM to merge conflicting records
 * - IMPORTANCE_BASED: Keep the most important record
 *
 * @author xander
 */
public class MemoryConflictResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryConflictResolver.class);

    private static final String MERGE_PROMPT = """
        You have multiple memory records about the same topic that may conflict.
        Merge them into a single, accurate, and up-to-date memory record.

        Topic: %s

        Records (from oldest to newest):
        %s

        Rules:
        1. Prefer newer information when facts conflict
        2. Preserve important details from all records
        3. Remove redundant or outdated information
        4. Keep the merged content concise (1-2 sentences)

        Output only the merged memory content:
        """;

    private final LLMProvider llmProvider;
    private final String model;

    public MemoryConflictResolver() {
        this(null, null);
    }

    public MemoryConflictResolver(LLMProvider llmProvider, String model) {
        this.llmProvider = llmProvider;
        this.model = model;
    }

    /**
     * Detect conflicting memories from a list of records.
     *
     * <p>Groups records by type and checks for semantic similarity.
     * Records with high similarity but different content are considered conflicts.
     *
     * @param records the memory records to check
     * @return list of conflict groups
     */
    public List<ConflictGroup> detectConflicts(List<MemoryRecord> records) {
        if (records == null || records.size() < 2) {
            return List.of();
        }

        // Group by type first
        Map<MemoryType, List<MemoryRecord>> byType = new EnumMap<>(MemoryType.class);
        for (MemoryRecord record : records) {
            MemoryType type = record.getType() != null ? record.getType() : MemoryType.FACT;
            byType.computeIfAbsent(type, k -> new ArrayList<>()).add(record);
        }

        List<ConflictGroup> conflicts = new ArrayList<>();

        // Check each type group for conflicts
        for (Map.Entry<MemoryType, List<MemoryRecord>> entry : byType.entrySet()) {
            List<MemoryRecord> typeRecords = entry.getValue();
            if (typeRecords.size() < 2) {
                continue;
            }

            // Simple conflict detection: records with similar first words/topic
            Map<String, List<MemoryRecord>> byTopic = groupByTopic(typeRecords);
            for (Map.Entry<String, List<MemoryRecord>> topicEntry : byTopic.entrySet()) {
                if (topicEntry.getValue().size() > 1) {
                    ConflictGroup group = new ConflictGroup(topicEntry.getKey(), topicEntry.getValue());
                    group.setConflictScore(calculateConflictScore(topicEntry.getValue()));
                    conflicts.add(group);
                }
            }
        }

        return conflicts;
    }

    /**
     * Resolve conflicts using the specified strategy.
     *
     * @param conflicts list of conflict groups
     * @param strategy  resolution strategy
     * @return resolved memory records (one per conflict group)
     */
    public List<MemoryRecord> resolve(List<ConflictGroup> conflicts, ConflictStrategy strategy) {
        if (conflicts == null || conflicts.isEmpty()) {
            return List.of();
        }

        List<MemoryRecord> resolved = new ArrayList<>();
        for (ConflictGroup group : conflicts) {
            MemoryRecord result = resolveGroup(group, strategy);
            if (result != null) {
                resolved.add(result);
            }
        }
        return resolved;
    }

    /**
     * Resolve a single conflict group.
     *
     * @param group    the conflict group
     * @param strategy resolution strategy
     * @return resolved memory record
     */
    public MemoryRecord resolveGroup(ConflictGroup group, ConflictStrategy strategy) {
        if (group == null || !group.hasConflict()) {
            return group != null && !group.getRecords().isEmpty() ? group.getRecords().getFirst() : null;
        }

        return switch (strategy) {
            case NEWEST_WINS -> group.getNewest();
            case IMPORTANCE_BASED -> group.getMostImportant();
            case MERGE, NEWEST_WITH_MERGE -> merge(group);
        };
    }

    /**
     * Merge multiple similar memories into one.
     *
     * @param group the conflict group to merge
     * @return merged memory record
     */
    public MemoryRecord merge(ConflictGroup group) {
        if (group == null || group.getRecords().isEmpty()) {
            return null;
        }

        List<MemoryRecord> records = group.getRecords();
        if (records.size() == 1) {
            return records.getFirst();
        }

        // If no LLM provider, fall back to newest
        if (llmProvider == null) {
            LOGGER.warn("No LLM provider for merge, falling back to newest");
            return group.getNewest();
        }

        // Sort by creation time (oldest first)
        List<MemoryRecord> sorted = new ArrayList<>(records);
        sorted.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));

        // Build records content
        StringBuilder recordsContent = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            MemoryRecord r = sorted.get(i);
            recordsContent.append(i + 1).append(". ")
                .append(r.getContent())
                .append(" (").append(r.getCreatedAt()).append(")\n");
        }

        String prompt = String.format(MERGE_PROMPT, group.getTopic(), recordsContent);
        String mergedContent = callLLM(prompt);

        if (mergedContent == null || mergedContent.isBlank()) {
            return group.getNewest();
        }

        // Create new merged record based on newest
        MemoryRecord newest = group.getNewest();
        return MemoryRecord.builder()
            .namespace(newest.getNamespace())
            .content(mergedContent.trim())
            .type(newest.getType())
            .importance(calculateMergedImportance(records))
            .sessionId(newest.getSessionId())
            .build();
    }

    /**
     * Check if two records are potentially conflicting.
     *
     * @param a first record
     * @param b second record
     * @return true if records may conflict
     */
    public boolean mayConflict(MemoryRecord a, MemoryRecord b) {
        if (a == null || b == null) {
            return false;
        }

        // Same type check
        if (a.getType() != b.getType()) {
            return false;
        }

        // Same namespace check
        if (!isSameNamespace(a.getNamespace(), b.getNamespace())) {
            return false;
        }

        // Simple content similarity check
        return hasOverlappingTopic(a.getContent(), b.getContent());
    }

    // ==================== Private Methods ====================

    private Map<String, List<MemoryRecord>> groupByTopic(List<MemoryRecord> records) {
        Map<String, List<MemoryRecord>> groups = new HashMap<>();

        for (MemoryRecord record : records) {
            String topic = extractTopic(record.getContent());
            groups.computeIfAbsent(topic, k -> new ArrayList<>()).add(record);
        }

        return groups;
    }

    private String extractTopic(String content) {
        if (content == null || content.isBlank()) {
            return "unknown";
        }

        // Extract first significant words as topic
        String[] words = content.toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder topic = new StringBuilder();
        int count = 0;

        for (String word : words) {
            if (isSignificantWord(word)) {
                if (topic.length() > 0) {
                    topic.append(' ');
                }
                topic.append(word);
                count++;
                if (count >= 3) {
                    break;
                }
            }
        }

        return topic.length() > 0 ? topic.toString() : "general";
    }

    private boolean isSignificantWord(String word) {
        if (word == null || word.length() < 3) {
            return false;
        }
        // Skip common stop words
        return !List.of("the", "and", "for", "that", "this", "with", "are", "was", "has", "have")
            .contains(word);
    }

    private boolean hasOverlappingTopic(String content1, String content2) {
        if (content1 == null || content2 == null) {
            return false;
        }

        String topic1 = extractTopic(content1);
        String topic2 = extractTopic(content2);

        return topic1.equals(topic2);
    }

    private boolean isSameNamespace(Namespace a, Namespace b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.toPath().equals(b.toPath());
    }

    private double calculateConflictScore(List<MemoryRecord> records) {
        if (records.size() < 2) {
            return 0.0;
        }
        // Simple score based on number of conflicts and time spread
        return Math.min(1.0, records.size() / 5.0);
    }

    private double calculateMergedImportance(List<MemoryRecord> records) {
        // Use the max importance from all records
        return records.stream()
            .mapToDouble(MemoryRecord::getImportance)
            .max()
            .orElse(0.5);
    }

    private String callLLM(String prompt) {
        try {
            var msgs = List.of(Message.of(RoleType.USER, prompt));
            var request = CompletionRequest.of(msgs, null, 0.3, model, "memory-conflict-resolver");
            var response = llmProvider.completion(request);

            if (response != null && response.choices != null && !response.choices.isEmpty()) {
                var choice = response.choices.getFirst();
                if (choice.message != null && choice.message.content != null) {
                    return choice.message.content.trim();
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to call LLM for merge", e);
        }
        return null;
    }
}
