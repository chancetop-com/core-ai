package ai.core.memory;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.CompletionRequest;
import ai.core.memory.longterm.LongTermMemory;
import ai.core.memory.longterm.MemoryScope;
import ai.core.tool.tools.MemoryRecallTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * @author xander
 */
public class UnifiedMemoryLifecycle extends AbstractLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(UnifiedMemoryLifecycle.class);

    private final LongTermMemory longTermMemory;
    private final int maxRecallRecords;
    private MemoryRecallTool memoryRecallTool;

    private MemoryScope currentScope;

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
        currentScope = extractScope(executionContext);
        LOGGER.debug("UnifiedMemoryLifecycle initialized, scope={}",
            currentScope != null ? currentScope.toKey() : "none");
    }

    @Override
    public void beforeModel(CompletionRequest request, ExecutionContext executionContext) {
        if (memoryRecallTool != null && currentScope != null) {
            memoryRecallTool.setCurrentScope(currentScope);
        }
    }

    @Override
    public void afterAgentRun(String query, AtomicReference<String> result, ExecutionContext executionContext) {
        currentScope = null;
    }

    private MemoryScope extractScope(ExecutionContext context) {
        if (context == null) {
            return null;
        }
        String userId = context.getUserId();
        return (userId != null && !userId.isBlank()) ? MemoryScope.forUser(userId) : null;
    }

    public LongTermMemory getLongTermMemory() {
        return longTermMemory;
    }

    public MemoryScope getCurrentScope() {
        return currentScope;
    }

    public int getMaxRecallRecords() {
        return maxRecallRecords;
    }
}
