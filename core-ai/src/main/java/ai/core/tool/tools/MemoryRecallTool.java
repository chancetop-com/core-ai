package ai.core.tool.tools;

import ai.core.memory.longterm.LongTermMemory;
import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.MemoryType;
import ai.core.memory.longterm.Namespace;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.ToolCallResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tool for LLM to proactively recall user memories from long-term memory.
 *
 * <p>This tool allows the LLM to decide when to query user memories,
 * similar to the LangMem pattern. Instead of auto-injecting memories
 * before every LLM call, this tool gives the LLM control over when
 * to retrieve relevant user information.
 *
 * <p>Usage scenarios:
 * <ul>
 *   <li>User asks about something they mentioned before</li>
 *   <li>Personalizing responses based on user preferences</li>
 *   <li>Referencing past interactions</li>
 * </ul>
 *
 * @author xander
 */
public class MemoryRecallTool extends ToolCall {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryRecallTool.class);
    public static final String TOOL_NAME = "search_memory_tool";

    public static Builder builder() {
        return new Builder();
    }

    private final LongTermMemory longTermMemory;
    private final int maxRecords;
    private volatile Namespace currentNamespace;

    public MemoryRecallTool(LongTermMemory longTermMemory) {
        this(longTermMemory, 5);
    }

    public MemoryRecallTool(LongTermMemory longTermMemory, int maxRecords) {
        this.longTermMemory = longTermMemory;
        this.maxRecords = maxRecords;
        initializeToolMetadata();
    }

    private void initializeToolMetadata() {
        super.setName(TOOL_NAME);
        super.setDescription("Search and recall relevant memories about the user. "
            + "Use this tool when you need to personalize your response based on user preferences, "
            + "recall something the user mentioned before, or reference past interactions.");
        super.setParameters(buildParameters());
    }

    @Override
    public ToolCallResult execute(String arguments) {
        long startTime = System.currentTimeMillis();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode params = mapper.readTree(arguments);

            String query = params.has("query") ? params.get("query").asText() : "";
            if (query.isEmpty()) {
                return ToolCallResult.failed("Error: 'query' parameter is required")
                    .withDuration(System.currentTimeMillis() - startTime);
            }

            // Parse optional type filter
            List<MemoryType> typeFilter = parseTypeFilter(params);

            // Get namespace from current context or fall back to LongTermMemory's current namespace
            Namespace namespace = currentNamespace != null ? currentNamespace : longTermMemory.getCurrentNamespace();
            if (namespace == null) {
                return ToolCallResult.completed("[No user context available - unable to recall memories]")
                    .withDuration(System.currentTimeMillis() - startTime);
            }

            // Recall memories
            List<MemoryRecord> memories;
            if (typeFilter.isEmpty()) {
                memories = longTermMemory.recall(namespace, query, maxRecords);
            } else {
                memories = longTermMemory.recall(query, maxRecords, typeFilter.toArray(new MemoryType[0]));
            }

            // Format result
            String result = formatMemories(memories);
            LOGGER.debug("Recalled {} memories for query: {}", memories.size(), truncate(query, 50));

            return ToolCallResult.completed(result)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("query", query)
                .withStats("memories_found", memories.size());

        } catch (Exception e) {
            LOGGER.error("Failed to recall memories", e);
            return ToolCallResult.failed("Error recalling memories: " + e.getMessage())
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private List<MemoryType> parseTypeFilter(JsonNode params) {
        List<MemoryType> types = new ArrayList<>();
        if (params.has("types") && params.get("types").isArray()) {
            for (JsonNode typeNode : params.get("types")) {
                try {
                    types.add(MemoryType.valueOf(typeNode.asText().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Unknown memory type: {}", typeNode.asText());
                }
            }
        }
        return types;
    }

    private String formatMemories(List<MemoryRecord> memories) {
        if (memories == null || memories.isEmpty()) {
            return "[No relevant memories found for this query]";
        }

        StringBuilder sb = new StringBuilder("[User Memory]\n");
        for (MemoryRecord record : memories) {
            sb.append("- ");
            if (record.getType() != null) {
                sb.append('[').append(record.getType().name()).append("] ");
            }
            sb.append(record.getContent()).append('\n');
        }
        return sb.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private List<ToolCallParameter> buildParameters() {
        return List.of(
            ToolCallParameter.builder()
                .name("query")
                .description("Search query to find relevant user memories. "
                    + "Use keywords related to what you want to know about the user.")
                .type(ToolCallParameterType.STRING)
                .classType(String.class)
                .required(true)
                .build(),
            ToolCallParameter.builder()
                .name("types")
                .description("Optional filter by memory types. "
                    + "Available types: FACT, PREFERENCE, GOAL, EPISODE, RELATIONSHIP")
                .type(ToolCallParameterType.LIST)
                .classType(List.class)
                .itemType(String.class)
                .required(false)
                .build()
        );
    }

    /**
     * Set the current namespace for memory recall.
     * Called by UnifiedMemoryLifecycle to update the namespace.
     */
    public void setCurrentNamespace(Namespace namespace) {
        this.currentNamespace = namespace;
    }

    public Namespace getCurrentNamespace() {
        return currentNamespace;
    }

    public LongTermMemory getLongTermMemory() {
        return longTermMemory;
    }

    public int getMaxRecords() {
        return maxRecords;
    }

    public static class Builder extends ToolCall.Builder<Builder, MemoryRecallTool> {
        private LongTermMemory longTermMemory;
        private int maxRecords = 5;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder longTermMemory(LongTermMemory longTermMemory) {
            this.longTermMemory = longTermMemory;
            return this;
        }

        public Builder maxRecords(int maxRecords) {
            this.maxRecords = maxRecords;
            return this;
        }

        public MemoryRecallTool build() {
            if (longTermMemory == null) {
                throw new IllegalStateException("longTermMemory is required");
            }
            return new MemoryRecallTool(longTermMemory, maxRecords);
        }
    }
}
