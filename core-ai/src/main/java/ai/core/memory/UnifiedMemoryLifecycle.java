package ai.core.memory;

import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.tool.tools.MemoryRecallTool;

/**
 * Lifecycle for unified memory management.
 * Creates and manages MemoryRecallTool for agent integration.
 *
 * @author xander
 */
public class UnifiedMemoryLifecycle extends AbstractLifecycle {

    private final Memory longTermMemory;
    private final int maxRecallRecords;
    private MemoryRecallTool memoryRecallTool;

    public UnifiedMemoryLifecycle(Memory longTermMemory) {
        this(longTermMemory, 5);
    }

    public UnifiedMemoryLifecycle(Memory longTermMemory, int maxRecallRecords) {
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

    public Memory getMemory() {
        return longTermMemory;
    }

    public int getMaxRecallRecords() {
        return maxRecallRecords;
    }
}
