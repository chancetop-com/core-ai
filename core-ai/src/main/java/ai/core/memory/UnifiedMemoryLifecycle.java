package ai.core.memory;

import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.memory.longterm.LongTermMemory;
import ai.core.tool.tools.MemoryRecallTool;

/**
 * Lifecycle for unified memory management.
 * Creates and manages MemoryRecallTool for agent integration.
 *
 * @author xander
 */
public class UnifiedMemoryLifecycle extends AbstractLifecycle {

    private final LongTermMemory longTermMemory;
    private final int maxRecallRecords;
    private MemoryRecallTool memoryRecallTool;

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

    public LongTermMemory getLongTermMemory() {
        return longTermMemory;
    }

    public int getMaxRecallRecords() {
        return maxRecallRecords;
    }
}
