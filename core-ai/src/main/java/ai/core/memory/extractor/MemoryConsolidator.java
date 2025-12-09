package ai.core.memory.extractor;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.memory.LongTermMemory;
import ai.core.memory.model.MemoryEntry;
import ai.core.memory.model.MemoryFilter;
import ai.core.memory.model.MemoryOperation;
import ai.core.memory.model.OperationType;
import ai.core.memory.util.MemoryUtils;
import core.framework.api.json.Property;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * Memory consolidator for Phase 2 of the Two-Phase Pipeline.
 * Determines how to handle new memories: ADD, UPDATE, DELETE, or NOOP.
 *
 * @author xander
 */
public class MemoryConsolidator {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryConsolidator.class);
    private static final double SIMILARITY_THRESHOLD = 0.8;
    private static final int MAX_SIMILAR_MEMORIES = 5;

    private static final String CONSOLIDATION_PROMPT = """
        # Memory Consolidation Task

        Determine how to handle a new memory given existing similar memories.

        ## New Memory
        %s

        ## Existing Similar Memories
        %s

        ## Instructions
        Analyze the relationship between the new memory and existing ones:

        1. **ADD** - New memory contains unique information not in existing memories
        2. **UPDATE** - New memory updates/refines an existing memory (specify which one by ID)
        3. **DELETE** - New memory contradicts an existing memory that should be removed
        4. **NOOP** - New memory is duplicate or irrelevant, skip it

        ## Decision Criteria
        - If new memory adds details to existing → UPDATE
        - If new memory directly contradicts existing → DELETE the old one
        - If new memory is essentially the same → NOOP
        - If no similar existing memory → ADD

        ## Output Format (JSON)
        {
            "operation": "ADD|UPDATE|DELETE|NOOP",
            "targetId": "existing memory ID if UPDATE or DELETE, null otherwise",
            "reason": "brief explanation"
        }

        Output JSON only:
        """;

    private final LLMProvider llmProvider;
    private final String model;
    private final LongTermMemory memoryStore;

    public MemoryConsolidator(LLMProvider llmProvider, String model, LongTermMemory memoryStore) {
        this.llmProvider = llmProvider;
        this.model = model;
        this.memoryStore = memoryStore;
    }

    /**
     * Determine the operation to perform for a candidate memory.
     *
     * @param candidate the candidate memory entry
     * @return memory operation result
     */
    public MemoryOperation determineOperation(MemoryEntry candidate) {
        if (candidate == null || candidate.getContent() == null) {
            return MemoryOperation.noop("Invalid candidate");
        }

        // Find similar existing memories
        List<MemoryEntry> similar = findSimilarMemories(candidate);

        if (similar.isEmpty()) {
            return MemoryOperation.add("No similar memories found");
        }

        // Use LLM to determine operation
        return determineWithLLM(candidate, similar);
    }

    /**
     * Simple consolidation without LLM (rule-based).
     * Use when LLM call is not desired.
     */
    public MemoryOperation determineOperationSimple(MemoryEntry candidate) {
        if (candidate == null || candidate.getContent() == null) {
            return MemoryOperation.noop("Invalid candidate");
        }

        List<MemoryEntry> similar = findSimilarMemories(candidate);

        if (similar.isEmpty()) {
            return MemoryOperation.add("No similar memories found");
        }

        // Simple rule: if very similar content exists, skip
        for (var existing : similar) {
            if (isHighlySimilar(candidate.getContent(), existing.getContent())) {
                return MemoryOperation.noop("Duplicate of " + existing.getId());
            }
        }

        // If same subject exists, update it
        if (candidate instanceof ai.core.memory.model.SemanticMemoryEntry sem
            && sem.getSubject() != null) {
            for (var existing : similar) {
                if (existing instanceof ai.core.memory.model.SemanticMemoryEntry existSem
                    && sem.getSubject().equalsIgnoreCase(existSem.getSubject())) {
                    return MemoryOperation.update(existing.getId(), "Same subject, updating");
                }
            }
        }

        return MemoryOperation.add("New information");
    }

    private List<MemoryEntry> findSimilarMemories(MemoryEntry candidate) {
        if (candidate.getEmbedding() == null) {
            // Without embedding, try text-based search
            var filter = MemoryFilter.forUser(candidate.getUserId())
                .withTypes(candidate.getType());
            return memoryStore.retrieve(candidate.getContent(), MAX_SIMILAR_MEMORIES, filter);
        }

        return memoryStore.findSimilar(
            candidate.getEmbedding(),
            MAX_SIMILAR_MEMORIES,
            SIMILARITY_THRESHOLD
        );
    }

    private MemoryOperation determineWithLLM(MemoryEntry candidate, List<MemoryEntry> similar) {
        String candidateJson = formatMemory(candidate);
        String existingJson = formatMemories(similar);
        String prompt = String.format(CONSOLIDATION_PROMPT, candidateJson, existingJson);

        try {
            var response = llmProvider.completion(CompletionRequest.of(
                List.of(Message.of(RoleType.USER, prompt)),
                null, 0.1, model, "memory-consolidator"
            ));

            if (response == null || response.choices == null || response.choices.isEmpty()) {
                return MemoryOperation.add("LLM response empty, defaulting to ADD");
            }

            String content = MemoryUtils.cleanJson(response.choices.getFirst().message.content);
            return parseOperation(content);
        } catch (Exception e) {
            LOGGER.warn("Failed to consolidate with LLM: {}", e.getMessage());
            return MemoryOperation.add("LLM consolidation failed, defaulting to ADD");
        }
    }

    private String formatMemory(MemoryEntry entry) {
        return String.format("""
            {
                "id": "%s",
                "type": "%s",
                "content": "%s",
                "importance": %.2f
            }""",
            entry.getId(),
            entry.getType(),
            MemoryUtils.escapeJson(entry.getContent()),
            entry.getImportance()
        );
    }

    private String formatMemories(List<MemoryEntry> entries) {
        if (entries.isEmpty()) {
            return "[]";
        }
        var sb = new StringBuilder(256);
        sb.append("[\n");
        for (int i = 0; i < entries.size(); i++) {
            sb.append(formatMemory(entries.get(i)));
            if (i < entries.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append(']');
        return sb.toString();
    }

    private MemoryOperation parseOperation(String jsonContent) {
        try {
            var dto = JSON.fromJSON(ConsolidationResultDto.class, jsonContent);
            OperationType type = parseOperationType(dto.operation);
            return new MemoryOperation(type, dto.targetId, dto.reason);
        } catch (Exception e) {
            LOGGER.warn("Failed to parse consolidation result: {}", e.getMessage());
            return MemoryOperation.add("Parse failed, defaulting to ADD");
        }
    }

    private OperationType parseOperationType(String operation) {
        if (operation == null) return OperationType.ADD;
        return switch (operation.toUpperCase(Locale.ROOT)) {
            case "UPDATE" -> OperationType.UPDATE;
            case "DELETE" -> OperationType.DELETE;
            case "NOOP" -> OperationType.NOOP;
            default -> OperationType.ADD;
        };
    }

    private boolean isHighlySimilar(String a, String b) {
        if (a == null || b == null) return false;
        // Simple check: if normalized strings are very similar
        String normA = a.toLowerCase(Locale.ROOT).trim();
        String normB = b.toLowerCase(Locale.ROOT).trim();
        return normA.equals(normB) || levenshteinSimilarity(normA, normB) > 0.9;
    }

    private double levenshteinSimilarity(String a, String b) {
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        int distance = levenshteinDistance(a, b);
        return 1.0 - ((double) distance / maxLen);
    }

    private int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    // DTOs for JSON parsing
    public static class ConsolidationResultDto {
        @Property(name = "operation")
        public String operation;

        @Property(name = "targetId")
        public String targetId;

        @Property(name = "reason")
        public String reason;
    }
}
