package ai.core.memory;

import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.tool.tools.MemoryRecallTool;

/**
 * @author xander
 */
public class MemoryLifecycle extends AbstractLifecycle {

    private final Memory memory;
    private final int maxRecallRecords;
    private MemoryRecallTool memoryRecallTool;

    public MemoryLifecycle(Memory memory) {
        this(memory, 5);
    }

    public MemoryLifecycle(Memory memory, int maxRecallRecords) {
        this.memory = memory;
        this.maxRecallRecords = maxRecallRecords;
    }

    public MemoryRecallTool getMemoryRecallTool() {
        if (memoryRecallTool == null) {
            memoryRecallTool = MemoryRecallTool.builder()
                .memory(memory)
                .maxRecords(maxRecallRecords)
                .build();
        }
        return memoryRecallTool;
    }

    public Memory getMemory() {
        return memory;
    }

    public int getMaxRecallRecords() {
        return maxRecallRecords;
    }
}
