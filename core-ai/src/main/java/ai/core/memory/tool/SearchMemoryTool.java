package ai.core.memory.tool;

import ai.core.memory.MemoryManager;
import ai.core.memory.model.MemoryEntry;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.ToolCallResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Tool for agents to autonomously search long-term memory.
 * Similar to langmem's approach, this allows agents to decide when to query memory
 * based on conversation context rather than always auto-retrieving.
 *
 * @author xander
 */
public class SearchMemoryTool extends ToolCall {

    private static final int DEFAULT_TOP_K = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MemoryManager memoryManager;
    private final String userId;
    private int defaultTopK = DEFAULT_TOP_K;

    public SearchMemoryTool(MemoryManager memoryManager, String userId) {
        if (memoryManager == null) {
            throw new IllegalArgumentException("memoryManager cannot be null");
        }
        this.memoryManager = memoryManager;
        this.userId = userId;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ToolCallResult execute(String text) {
        long startTime = System.currentTimeMillis();
        try {
            JsonNode params = MAPPER.readTree(text);

            String query = params.has("query") ? params.get("query").asText() : "";
            if (query.isEmpty()) {
                return ToolCallResult.failed("Error: 'query' parameter is required")
                    .withDuration(System.currentTimeMillis() - startTime);
            }

            int topK = params.has("topK") ? params.get("topK").asInt() : defaultTopK;

            List<MemoryEntry> memories = memoryManager.search(query, userId, topK);

            if (memories.isEmpty()) {
                return ToolCallResult.completed("No relevant memories found for: " + query)
                    .withDuration(System.currentTimeMillis() - startTime);
            }

            String result = formatMemories(memories);
            return ToolCallResult.completed(result)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("query", query)
                .withStats("resultsCount", memories.size());

        } catch (Exception e) {
            return ToolCallResult.failed("Error searching memory: " + e.getMessage())
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private String formatMemories(List<MemoryEntry> memories) {
        var sb = new StringBuilder("Found ").append(memories.size()).append(" relevant memories:\n");
        for (int i = 0; i < memories.size(); i++) {
            sb.append(i + 1).append(". ").append(memories.get(i).getContent()).append('\n');
        }
        return sb.toString();
    }

    @Override
    public List<ToolCallParameter> getParameters() {
        return List.of(
            ToolCallParameter.builder()
                .name("query")
                .description("Search query to find relevant memories about the user")
                .type(ToolCallParameterType.STRING)
                .classType(String.class)
                .required(true)
                .build(),
            ToolCallParameter.builder()
                .name("topK")
                .description("Maximum number of memories to return (default: " + DEFAULT_TOP_K + ")")
                .type(ToolCallParameterType.INTEGER)
                .classType(Integer.class)
                .required(false)
                .build()
        );
    }

    public static class Builder extends ToolCall.Builder<Builder, SearchMemoryTool> {
        private MemoryManager memoryManager;
        private String userId;
        private Integer defaultTopK;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder memoryManager(MemoryManager memoryManager) {
            this.memoryManager = memoryManager;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder defaultTopK(Integer topK) {
            this.defaultTopK = topK;
            return this;
        }

        public SearchMemoryTool build() {
            var tool = new SearchMemoryTool(memoryManager, userId);

            try {
                super.build(tool);
            } catch (RuntimeException e) {
                if (e.getMessage() != null
                    && (e.getMessage().contains("name is required")
                        || e.getMessage().contains("description is required"))) {
                    name("search_memory");
                    description("Search user's long-term memory for relevant information. " +
                        "Use this when you need to recall facts, preferences, or past context about the user.");
                    super.build(tool);
                } else {
                    throw e;
                }
            }

            if (defaultTopK != null) {
                tool.defaultTopK = defaultTopK;
            }

            return tool;
        }
    }
}
