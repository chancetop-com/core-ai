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

    public List<MemoryRecord> recall(String query, MemoryScope scope, int maxRecords) {
        if (query == null || query.isBlank() || scope == null) {
            return List.of();
        }
        return longTermMemory.recall(scope, query, maxRecords);
    }

    public List<MemoryRecord> recall(String query) {
        return recall(query, longTermMemory.getCurrentScope(), defaultMaxRecords);
    }

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

    public boolean hasMemories() {
        return longTermMemory != null && longTermMemory.hasMemories();
    }

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

    public LongTermMemory getLongTermMemory() {
        return longTermMemory;
    }

    public ContextBudgetManager getBudgetManager() {
        return budgetManager;
    }
}
