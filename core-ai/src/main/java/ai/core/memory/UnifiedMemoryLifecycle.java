package ai.core.memory;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.CompletionRequest;
import ai.core.memory.longterm.LongTermMemory;
import ai.core.memory.longterm.Namespace;
import ai.core.tool.tools.MemoryRecallTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Lifecycle for long-term memory management in agent execution.
 *
 * <p>This lifecycle provides memory recall through a Tool (search_memory_tool),
 * allowing the LLM to proactively decide when to query user memories,
 * similar to the LangMem pattern.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Initialize memory session before agent run</li>
 *   <li>Update memory tool's namespace before each LLM call</li>
 *   <li>End session and trigger memory extraction after agent run</li>
 * </ul>
 *
 * @author xander
 */
public class UnifiedMemoryLifecycle extends AbstractLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(UnifiedMemoryLifecycle.class);

    private final LongTermMemory longTermMemory;
    private final int maxRecallRecords;
    private MemoryRecallTool memoryRecallTool;

    private Namespace currentNamespace;
    private boolean sessionStarted;

    public UnifiedMemoryLifecycle(LongTermMemory longTermMemory) {
        this(longTermMemory, 5);
    }

    public UnifiedMemoryLifecycle(LongTermMemory longTermMemory, int maxRecallRecords) {
        this.longTermMemory = longTermMemory;
        this.maxRecallRecords = maxRecallRecords;
    }

    public MemoryRecallTool getMemoryRecallTool() {
        if (memoryRecallTool == null) {
            memoryRecallTool = MemoryRecallTool.builder()
                .longTermMemory(longTermMemory)
                .maxRecords(maxRecallRecords)
                .build();
        }
        return memoryRecallTool;
    }

    @Override
    public void beforeAgentRun(AtomicReference<String> query, ExecutionContext executionContext) {
        initializeSession(executionContext);
        LOGGER.debug("UnifiedMemoryLifecycle initialized, namespace={}",
            currentNamespace != null ? currentNamespace.toPath() : "none");
    }

    @Override
    public void beforeModel(CompletionRequest request, ExecutionContext executionContext) {
        // Update the tool's namespace so it can access correct user memories
        if (memoryRecallTool != null && currentNamespace != null) {
            memoryRecallTool.setCurrentNamespace(currentNamespace);
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

    public LongTermMemory getLongTermMemory() {
        return longTermMemory;
    }

    public Namespace getCurrentNamespace() {
        return currentNamespace;
    }

    public int getMaxRecallRecords() {
        return maxRecallRecords;
    }
}
