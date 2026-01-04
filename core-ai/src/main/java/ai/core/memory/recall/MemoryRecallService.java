package ai.core.memory.recall;

import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.memory.budget.ContextBudgetManager;
import ai.core.memory.longterm.LongTermMemory;
import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.MemoryScope;

import java.util.List;
import java.util.UUID;

/**
 * Service for recalling and formatting long-term memories.
 *
 * <p>Recalls relevant memories based on user query and formats them
 * as Tool Call messages for injection into conversation context.
 *
 * @author xander
 */
public class MemoryRecallService {

    private static final String TOOL_NAME = "recall_memory";
    private static final String MEMORY_HEADER = "[User Memory]";

    private final LongTermMemory longTermMemory;
    private final ContextBudgetManager budgetManager;
    private final int defaultMaxRecords;

    public MemoryRecallService(LongTermMemory longTermMemory) {
        this(longTermMemory, new ContextBudgetManager(), 5);
    }

    public MemoryRecallService(LongTermMemory longTermMemory,
                               ContextBudgetManager budgetManager,
                               int defaultMaxRecords) {
        this.longTermMemory = longTermMemory;
        this.budgetManager = budgetManager;
        this.defaultMaxRecords = defaultMaxRecords;
    }

    /**
     * Recall memories relevant to the query.
     *
     * @param query     the query to search for
     * @param scope the scope to search in
     * @param maxRecords maximum number of records to return
     * @return list of relevant memory records
     */
    public List<MemoryRecord> recall(String query, MemoryScope scope, int maxRecords) {
        if (query == null || query.isBlank() || scope == null) {
            return List.of();
        }
        return longTermMemory.recall(scope, query, maxRecords);
    }

    /**
     * Recall memories using current session scope.
     *
     * @param query the query to search for
     * @return list of relevant memory records
     */
    public List<MemoryRecord> recall(String query) {
        return recall(query, longTermMemory.getCurrentScope(), defaultMaxRecords);
    }

    /**
     * Recall memories with budget constraint.
     *
     * @param query           the query to search for
     * @param currentMessages current message history for budget calculation
     * @param systemPrompt    system prompt for budget calculation
     * @return list of memories within budget
     */
    public List<MemoryRecord> recallWithBudget(String query,
                                                List<Message> currentMessages,
                                                String systemPrompt) {
        // First recall more candidates
        List<MemoryRecord> candidates = recall(query, longTermMemory.getCurrentScope(),
            defaultMaxRecords * 2);

        if (candidates.isEmpty()) {
            return List.of();
        }

        // Select within budget
        int budget = budgetManager.calculateAvailableBudget(currentMessages, systemPrompt);
        return budgetManager.selectWithinBudget(candidates, budget);
    }

    /**
     * Format memories as Tool Call messages for injection.
     *
     * <p>Creates a pair of messages:
     * 1. ASSISTANT message with tool_calls
     * 2. TOOL message with the memory content
     *
     * @param memories the memories to format
     * @return list of messages (2 messages if memories exist, empty otherwise)
     */
    public List<Message> formatAsToolMessages(List<MemoryRecord> memories) {
        if (memories == null || memories.isEmpty()) {
            return List.of();
        }

        String toolCallId = "memory_" + UUID.randomUUID().toString().substring(0, 8);
        String content = formatMemoryContent(memories);

        // Create ASSISTANT message with tool_calls
        FunctionCall toolCall = FunctionCall.of(toolCallId, "function", TOOL_NAME,
            "{\"query\":\"recall relevant memories\"}");
        Message assistantMsg = Message.of(RoleType.ASSISTANT, "", null, null, null, List.of(toolCall));

        // Create TOOL message with memory content
        Message toolMsg = Message.of(RoleType.TOOL, content, TOOL_NAME, toolCallId, null, null);

        return List.of(assistantMsg, toolMsg);
    }

    /**
     * Format memory records as readable content.
     *
     * @param memories the memories to format
     * @return formatted string content
     */
    public String formatMemoryContent(List<MemoryRecord> memories) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(MEMORY_HEADER).append('\n');

        for (MemoryRecord record : memories) {
            sb.append("- ");
            if (record.getType() != null) {
                sb.append('[').append(record.getType().name()).append("] ");
            }
            sb.append(record.getContent()).append('\n');
        }

        return sb.toString().trim();
    }

    /**
     * Check if there are any memories available for recall.
     *
     * @return true if memories exist
     */
    public boolean hasMemories() {
        return longTermMemory != null && longTermMemory.hasMemories();
    }

    /**
     * Extract the latest user query from messages.
     *
     * @param messages the message list
     * @return the latest user message content, or null if not found
     */
    public String extractLatestUserQuery(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        // Search from the end to find the latest user message
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg.role == RoleType.USER && msg.content != null && !msg.content.isBlank()) {
                return msg.content;
            }
        }
        return null;
    }

    /**
     * Get the underlying long-term memory.
     *
     * @return the long-term memory instance
     */
    public LongTermMemory getLongTermMemory() {
        return longTermMemory;
    }

    /**
     * Get the budget manager.
     *
     * @return the context budget manager
     */
    public ContextBudgetManager getBudgetManager() {
        return budgetManager;
    }
}
