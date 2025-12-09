package ai.core.memory.tool;

import ai.core.memory.MemoryManager;
import ai.core.memory.model.EpisodicMemoryEntry;
import ai.core.memory.model.MemoryContext;
import ai.core.memory.model.MemoryFilter;
import ai.core.memory.model.MemoryType;
import ai.core.memory.model.RetrievalOptions;
import ai.core.memory.model.SemanticMemoryEntry;
import ai.core.memory.util.MemoryUtils;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tool for deep memory search (Layer 2 retrieval).
 * Allows agent to proactively search user memories when basic context is insufficient.
 *
 * @author xander
 */
public class SearchMemoryTool extends ToolCall {
    public static final String TOOL_NAME = "search_memory";
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchMemoryTool.class);
    private static final int DEFAULT_TOP_K = 10;

    private static final String TOOL_DESC = """
        Search user's long-term memories for relevant information.
        Use this tool when you need to know:
        - User preferences or personal information
        - Past conversations or decisions
        - Previous interactions or experiences
        - Any historical context about the user

        This provides deep search with filters. Use when basic context is insufficient.
        """;

    public static Builder builder() {
        return new Builder();
    }

    private MemoryManager memoryManager;
    private String userId;

    @Override
    @SuppressWarnings("unchecked")
    public ToolCallResult execute(String arguments) {
        var startTime = System.currentTimeMillis();

        try {
            var argsMap = JSON.fromJSON(Map.class, arguments);
            var query = (String) argsMap.get("query");
            var memoryType = (String) argsMap.get("type");
            var timeRange = (String) argsMap.get("time_range");
            var topKObj = argsMap.get("top_k");
            int topK = DEFAULT_TOP_K;
            if (topKObj instanceof Number num) {
                topK = num.intValue();
            }

            if (query == null || query.isBlank()) {
                return ToolCallResult.failed("Error: query parameter is required")
                    .withDuration(System.currentTimeMillis() - startTime);
            }
            var filter = buildFilter(memoryType, timeRange);
            var options = RetrievalOptions.deep(topK).withFilter(filter);
            var context = memoryManager.retrieve(query, userId, options);

            String result = formatResults(context);
            LOGGER.info("Memory search completed: query='{}', topK={}, found {} results",
                MemoryUtils.truncate(query), topK, context.size());

            return ToolCallResult.completed(result)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("query", query)
                .withStats("topK", topK)
                .withStats("results", context.size());

        } catch (Exception e) {
            var error = "Failed to search memory: " + e.getMessage();
            LOGGER.error(error, e);
            return ToolCallResult.failed(error)
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private MemoryFilter buildFilter(String memoryType, String timeRange) {
        var filter = MemoryFilter.forUser(userId);

        if (memoryType != null && !memoryType.isBlank() && !"ALL".equalsIgnoreCase(memoryType)) {
            try {
                filter.withTypes(MemoryType.valueOf(memoryType.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Invalid memory type: {}", memoryType);
            }
        }

        if (timeRange != null && !timeRange.isBlank()) {
            Instant after = parseTimeRange(timeRange);
            if (after != null) {
                filter.after(after);
            }
        }

        return filter;
    }

    private Instant parseTimeRange(String timeRange) {
        if (timeRange == null) return null;

        return switch (timeRange.toLowerCase(Locale.ROOT)) {
            case "last_hour" -> Instant.now().minus(Duration.ofHours(1));
            case "last_day", "today" -> Instant.now().minus(Duration.ofDays(1));
            case "last_week" -> Instant.now().minus(Duration.ofDays(7));
            case "last_month" -> Instant.now().minus(Duration.ofDays(30));
            case "last_year" -> Instant.now().minus(Duration.ofDays(365));
            default -> null;
        };
    }

    private String formatResults(MemoryContext context) {
        if (context.isEmpty()) {
            return "No relevant memories found.";
        }

        var sb = new StringBuilder(512);
        sb.append("## Retrieved Memories\n\n");

        var semanticMemories = context.getSemanticMemories();
        if (!semanticMemories.isEmpty()) {
            sb.append("### User Information\n");
            for (var memory : semanticMemories) {
                sb.append("- ").append(memory.getContent());
                if (memory instanceof SemanticMemoryEntry sem && sem.getCategory() != null) {
                    sb.append(" [").append(sem.getCategory()).append(']');
                }
                sb.append('\n');
            }
            sb.append('\n');
        }

        var episodicMemories = context.getEpisodicMemories();
        if (!episodicMemories.isEmpty()) {
            sb.append("### Past Experiences\n");
            for (var memory : episodicMemories) {
                formatEpisodicMemory(sb, memory);
            }
        }

        return sb.toString();
    }

    private void formatEpisodicMemory(StringBuilder sb, ai.core.memory.model.MemoryEntry memory) {
        if (!(memory instanceof EpisodicMemoryEntry ep)) {
            sb.append("- ").append(memory.getContent()).append('\n');
            return;
        }
        sb.append("- ");
        appendIfNotNull(sb, "Situation: ", ep.getSituation(), "");
        appendIfNotNull(sb, " | Action: ", ep.getAction(), "");
        appendIfNotNull(sb, " | Outcome: ", ep.getOutcome(), "");
        sb.append('\n');
    }

    private void appendIfNotNull(StringBuilder sb, String prefix, String value, String suffix) {
        if (value != null) {
            sb.append(prefix).append(value).append(suffix);
        }
    }

    /**
     * Builder for SearchMemoryTool.
     */
    public static class Builder extends ToolCall.Builder<Builder, SearchMemoryTool> {
        private MemoryManager memoryManager;
        private String userId;

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

        public SearchMemoryTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(List.of(
                ToolCallParameter.builder()
                    .name("query")
                    .description("The search query to find relevant memories")
                    .classType(String.class)
                    .required(true)
                    .build(),
                ToolCallParameter.builder()
                    .name("type")
                    .description("Memory type filter: SEMANTIC (facts/preferences), EPISODIC (events), or ALL (default)")
                    .classType(String.class)
                    .required(false)
                    .build(),
                ToolCallParameter.builder()
                    .name("time_range")
                    .description("Time range filter: last_hour, last_day, last_week, last_month, last_year")
                    .classType(String.class)
                    .required(false)
                    .build(),
                ToolCallParameter.builder()
                    .name("top_k")
                    .description("Maximum number of results to return (default: 10)")
                    .classType(Integer.class)
                    .required(false)
                    .build()
            ));

            var tool = new SearchMemoryTool();
            tool.memoryManager = this.memoryManager;
            tool.userId = this.userId;
            build(tool);
            return tool;
        }
    }
}
