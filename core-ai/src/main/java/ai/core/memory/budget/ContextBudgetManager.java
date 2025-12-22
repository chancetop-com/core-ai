package ai.core.memory.budget;

import ai.core.document.Tokenizer;
import ai.core.llm.LLMModelContextRegistry;
import ai.core.llm.domain.Message;
import ai.core.memory.MessageTokenCounter;
import ai.core.memory.longterm.MemoryRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Manages context token budget for memory injection.
 *
 * <p>Calculates available budget for memory based on:
 * - Model's max context window
 * - Current message history tokens
 * - System prompt tokens
 * - Reserved tokens for new generation
 *
 * @author xander
 */
public class ContextBudgetManager {

    private static final double DEFAULT_MEMORY_BUDGET_RATIO = 0.2;
    private static final double DEFAULT_RESERVED_FOR_GENERATION = 0.3;
    private static final int DEFAULT_MAX_TOKENS = 128000;
    private static final int MIN_MEMORY_BUDGET = 200;

    private static int getModelMaxTokens(String model) {
        if (model == null) {
            return DEFAULT_MAX_TOKENS;
        }
        int maxTokens = LLMModelContextRegistry.getInstance().getMaxInputTokens(model);
        return maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS;
    }

    private final int maxContextTokens;
    private final double memoryBudgetRatio;
    private final double reservedForGeneration;

    public ContextBudgetManager() {
        this(DEFAULT_MAX_TOKENS, DEFAULT_MEMORY_BUDGET_RATIO, DEFAULT_RESERVED_FOR_GENERATION);
    }

    public ContextBudgetManager(String model) {
        this(getModelMaxTokens(model), DEFAULT_MEMORY_BUDGET_RATIO, DEFAULT_RESERVED_FOR_GENERATION);
    }

    public ContextBudgetManager(int maxContextTokens, double memoryBudgetRatio, double reservedForGeneration) {
        this.maxContextTokens = maxContextTokens;
        this.memoryBudgetRatio = memoryBudgetRatio;
        this.reservedForGeneration = reservedForGeneration;
    }

    /**
     * Calculate available token budget for memory injection.
     *
     * @param currentMessages current message history
     * @param systemPrompt    system prompt content
     * @return available tokens for memory
     */
    public int calculateAvailableBudget(List<Message> currentMessages, String systemPrompt) {
        int usedTokens = 0;

        // Count system prompt tokens
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            usedTokens += Tokenizer.tokenCount(systemPrompt);
        }

        // Count message history tokens
        if (currentMessages != null && !currentMessages.isEmpty()) {
            usedTokens += MessageTokenCounter.count(currentMessages);
        }

        // Calculate remaining budget after reserving for generation
        int reservedTokens = (int) (maxContextTokens * reservedForGeneration);
        int availableForAll = maxContextTokens - usedTokens - reservedTokens;

        // Allocate portion to memory
        int memoryBudget = (int) (availableForAll * memoryBudgetRatio);

        return Math.max(MIN_MEMORY_BUDGET, memoryBudget);
    }

    /**
     * Select memories that fit within the given token budget.
     * Prioritizes by effective score (importance, recency, similarity).
     *
     * @param candidates memory candidates sorted by relevance
     * @param budget     available token budget
     * @return selected memories within budget
     */
    public List<MemoryRecord> selectWithinBudget(List<MemoryRecord> candidates, int budget) {
        if (candidates == null || candidates.isEmpty() || budget <= 0) {
            return List.of();
        }

        // Sort by importance and decay factor (descending)
        List<MemoryRecord> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(
            (MemoryRecord r) -> r.getImportance() * r.getDecayFactor()
        ).reversed());

        List<MemoryRecord> selected = new ArrayList<>();
        int usedTokens = 0;

        for (MemoryRecord record : sorted) {
            int recordTokens = estimateRecordTokens(record);
            if (usedTokens + recordTokens <= budget) {
                selected.add(record);
                usedTokens += recordTokens;
            } else if (selected.isEmpty()) {
                // Always include at least one if possible
                selected.add(record);
                break;
            }
        }

        return selected;
    }

    /**
     * Estimate token count for a memory record.
     *
     * @param record the memory record
     * @return estimated token count
     */
    public int estimateRecordTokens(MemoryRecord record) {
        if (record == null || record.getContent() == null) {
            return 0;
        }
        // Add overhead for formatting (bullet point, newline, type label)
        int contentTokens = Tokenizer.tokenCount(record.getContent());
        return contentTokens + 10; // ~10 tokens overhead for formatting
    }

    /**
     * Get the max context tokens for this manager.
     *
     * @return max context tokens
     */
    public int getMaxContextTokens() {
        return maxContextTokens;
    }
}
