package ai.core.memory;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.memory.budget.ContextBudgetManager;
import ai.core.memory.longterm.LongTermMemory;
import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.Namespace;
import ai.core.memory.recall.MemoryRecallService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lifecycle for automatic long-term memory recall before LLM calls.
 * Memory is injected as Tool Call messages for consistency with short-term memory.
 *
 * @author xander
 */
public class UnifiedMemoryLifecycle extends AbstractLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(UnifiedMemoryLifecycle.class);
    private static final String MEMORY_TOOL_NAME = "recall_long_term_memory";

    private final LongTermMemory longTermMemory;
    private final MemoryRecallService recallService;

    private Namespace currentNamespace;
    private boolean sessionStarted;
    private boolean memoryInjected;

    public UnifiedMemoryLifecycle(LongTermMemory longTermMemory) {
        this(longTermMemory, 5);
    }

    public UnifiedMemoryLifecycle(LongTermMemory longTermMemory, int maxRecallRecords) {
        this.longTermMemory = longTermMemory;
        this.recallService = new MemoryRecallService(longTermMemory, new ContextBudgetManager(), maxRecallRecords);
    }

    @Override
    public void beforeAgentRun(AtomicReference<String> query, ExecutionContext executionContext) {
        initializeSession(executionContext);
        memoryInjected = false;
        LOGGER.debug("UnifiedMemoryLifecycle initialized, namespace={}",
            currentNamespace != null ? currentNamespace.toPath() : "none");
    }

    @Override
    public void beforeModel(CompletionRequest request, ExecutionContext executionContext) {
        if (memoryInjected) {
            return;
        }
        try {
            injectLongTermMemory(request);
        } catch (Exception e) {
            LOGGER.error("Failed to inject long-term memory", e);
        } finally {
            memoryInjected = true;
        }
    }

    @Override
    public void afterAgentRun(String query, AtomicReference<String> result, ExecutionContext executionContext) {
        try {
            if (sessionStarted) {
                longTermMemory.endSession();
            }
        } finally {
            currentNamespace = null;
            sessionStarted = false;
            memoryInjected = false;
        }
    }

    private void initializeSession(ExecutionContext context) {
        sessionStarted = false;
        currentNamespace = extractNamespace(context);
        startSessionIfNeeded(context);
    }

    private Namespace extractNamespace(ExecutionContext context) {
        if (context == null) {
            return null;
        }
        String userId = context.getUserId();
        return (userId != null && !userId.isBlank()) ? Namespace.forUser(userId) : null;
    }

    private void startSessionIfNeeded(ExecutionContext context) {
        if (currentNamespace == null || context == null) {
            return;
        }
        String sessionId = context.getSessionId();
        if (sessionId != null) {
            longTermMemory.startSession(currentNamespace, sessionId);
            sessionStarted = true;
        }
    }

    private void injectLongTermMemory(CompletionRequest request) {
        if (request.messages == null || request.messages.isEmpty()) {
            return;
        }

        String query = extractLatestUserQuery(request.messages);
        if (query == null || query.isBlank()) {
            return;
        }

        String systemPrompt = extractSystemPrompt(request.messages);
        List<MemoryRecord> memories = recallService.recallWithBudget(query, request.messages, systemPrompt);
        if (memories.isEmpty()) {
            return;
        }

        LOGGER.info("Injecting {} memories for: {}...", memories.size(),
            query.length() > 50 ? query.substring(0, 50) : query);
        insertMemoryToolCallPair(request.messages, memories);
    }

    private void insertMemoryToolCallPair(List<Message> messages, List<MemoryRecord> memories) {
        String content = recallService.formatMemoryContent(memories);
        String toolCallId = "ltm_" + UUID.randomUUID().toString().substring(0, 8);
        int insertIdx = (!messages.isEmpty() && messages.getFirst().role == RoleType.SYSTEM) ? 1 : 0;

        var toolCall = FunctionCall.of(toolCallId, "function", MEMORY_TOOL_NAME, "{\"query\":\"recall\"}");
        messages.add(insertIdx, Message.of(RoleType.ASSISTANT, "", null, null, null, List.of(toolCall)));
        messages.add(insertIdx + 1, Message.of(RoleType.TOOL, content, MEMORY_TOOL_NAME, toolCallId, null, null));
    }

    private String extractLatestUserQuery(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg.role == RoleType.USER && msg.content != null && !msg.content.isBlank()) {
                return msg.content;
            }
        }
        return null;
    }

    private String extractSystemPrompt(List<Message> messages) {
        Message first = messages.getFirst();
        return (first.role == RoleType.SYSTEM && first.content != null) ? first.content : "";
    }

    public LongTermMemory getLongTermMemory() {
        return longTermMemory;
    }

    public MemoryRecallService getRecallService() {
        return recallService;
    }
}
