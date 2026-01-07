package ai.core.tool.tools;

import ai.core.memory.Memory;
import ai.core.memory.MemoryRecord;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.ToolCallResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * @author xander
 */
public final class MemoryRecallTool extends ToolCall {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryRecallTool.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final String TOOL_NAME = "search_memory_tool";

    public static Builder builder() {
        return new Builder();
    }

    private final Memory longTermMemory;
    private final int maxRecords;
    private final Supplier<String> userIdSupplier;

    public MemoryRecallTool(Memory longTermMemory, Supplier<String> userIdSupplier) {
        this(longTermMemory, userIdSupplier, 5);
    }

    public MemoryRecallTool(Memory longTermMemory, Supplier<String> userIdSupplier, int maxRecords) {
        this.longTermMemory = longTermMemory;
        this.userIdSupplier = userIdSupplier;
        this.maxRecords = maxRecords;
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
            JsonNode params = OBJECT_MAPPER.readTree(arguments);

            String query = params.has("query") ? params.get("query").asText() : "";
            if (query.isEmpty()) {
                return ToolCallResult.failed("Error: 'query' parameter is required")
                    .withDuration(System.currentTimeMillis() - startTime);
            }

            String userId = userIdSupplier.get();
            if (userId == null || userId.isEmpty()) {
                return ToolCallResult.failed("Error: userId is not available")
                    .withDuration(System.currentTimeMillis() - startTime);
            }

            List<MemoryRecord> memories = longTermMemory.retrieve(userId, query, maxRecords);

            String result = formatMemories(memories);
            LOGGER.debug("Retrieved {} memories for user: {}, query: {}", memories.size(), userId, truncate(query, 50));

            return ToolCallResult.completed(result)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("query", query)
                .withStats("memories_found", memories.size());

        } catch (Exception e) {
            LOGGER.error("Failed to retrieve memories", e);
            return ToolCallResult.failed("Error retrieving memories: " + e.getMessage())
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private String formatMemories(List<MemoryRecord> memories) {
        if (memories == null || memories.isEmpty()) {
            return "[No relevant memories found for this query]";
        }

        StringBuilder sb = new StringBuilder("[User Memory]\n");
        for (MemoryRecord record : memories) {
            sb.append("- ").append(record.getContent()).append('\n');
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
                .build()
        );
    }

    public Memory getMemory() {
        return longTermMemory;
    }

    public int getMaxRecords() {
        return maxRecords;
    }

    public static class Builder extends ToolCall.Builder<Builder, MemoryRecallTool> {
        private Memory longTermMemory;
        private Supplier<String> userIdSupplier;
        private int maxRecords = 5;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder longTermMemory(Memory longTermMemory) {
            this.longTermMemory = longTermMemory;
            return this;
        }

        public Builder userIdSupplier(Supplier<String> userIdSupplier) {
            this.userIdSupplier = userIdSupplier;
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
            if (userIdSupplier == null) {
                throw new IllegalStateException("userIdSupplier is required");
            }
            return new MemoryRecallTool(longTermMemory, userIdSupplier, maxRecords);
        }
    }
}
