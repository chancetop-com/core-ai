package ai.core.cli.memory;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;

/**
 * Tool that returns the full memory extraction specification.
 * Extraction agents call this to get file formats, knowledge types,
 * and extraction procedures — decoupled from the system prompt.
 */
public final class MemoryExtractionTool extends ToolCall {

    public static final String TOOL_NAME = "get_memory_extraction_spec";

    private static volatile boolean directMode = false;

    static void setDirectMode(boolean direct) {
        directMode = direct;
    }

    static String getCurrentSpec() {
        return directMode ? MemoryDirectExtractionSpecs.DIRECT_EXTRACTION_SPEC : MemoryExtractionSpecs.EXTRACTION_SPEC;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.completed(directMode ? MemoryDirectExtractionSpecs.DIRECT_EXTRACTION_SPEC : MemoryExtractionSpecs.EXTRACTION_SPEC);
    }

    public static final class Builder extends ToolCall.Builder<Builder, MemoryExtractionTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public MemoryExtractionTool build() {
            this.name(TOOL_NAME);
            this.description("""
                    Returns the full memory extraction specification: 4-layer hierarchy,
                    file formats, knowledge types, extraction procedures, and rules.
                    Call this tool when you need to extract knowledge from conversations
                    into the memory system (daily-logs, episodes, knowledge wiki pages).""");
            var tool = new MemoryExtractionTool();
            build(tool);
            return tool;
        }
    }
}
