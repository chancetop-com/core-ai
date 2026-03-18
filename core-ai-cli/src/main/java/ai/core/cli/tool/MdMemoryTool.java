package ai.core.cli.tool;

import ai.core.cli.memory.MdMemoryProvider;
import ai.core.cli.memory.MdMemoryProvider.MemoryEntry;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.ToolCallResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

public final class MdMemoryTool extends ToolCall {

    private static final Logger LOGGER = LoggerFactory.getLogger(MdMemoryTool.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final String TOOL_NAME = "md_memory_tool";

    public static Builder builder() {
        return new Builder();
    }

    private static List<ToolCallParameter> buildParameters() {
        return List.of(
                actionParam(),
                stringParam("query", "Search keyword. Required for search."),
                stringParam("path", "Relative path to a memory file (e.g. 'user_role.md'). Required for get."),
                lineParams("from", "Start line number for get (optional, 1-based)."),
                lineParams("lines", "Number of lines to read for get (optional).")
        );
    }

    private static ToolCallParameter actionParam() {
        return ToolCallParameter.builder()
                .name("action")
                .description("'search': search memories by keyword; 'get': read a specific memory file or lines from it")
                .type(ToolCallParameterType.STRING)
                .required(true)
                .enums(List.of("search", "get"))
                .build();
    }

    private static ToolCallParameter stringParam(String paramName, String desc) {
        return ToolCallParameter.builder()
                .name(paramName)
                .description(desc)
                .type(ToolCallParameterType.STRING)
                .required(false)
                .build();
    }

    private static ToolCallParameter lineParams(String paramName, String desc) {
        return ToolCallParameter.builder()
                .name(paramName)
                .description(desc)
                .type(ToolCallParameterType.INTEGER)
                .required(false)
                .build();
    }

    private final MdMemoryProvider provider;

    private MdMemoryTool(MdMemoryProvider provider) {
        this.provider = provider;
        setName(TOOL_NAME);
        setNeedAuth(false);
        setDescription("""
                Search and read structured markdown memory files in .core-ai/memory/ directory.
                Each memory is a .md file with YAML frontmatter (name, description, type) and MEMORY.md as index.
                Use action=search to find memories by keyword across all files.
                Use action=get to read a specific memory file content with optional line range.
                To create, edit, or delete memory files, use write_file, edit_file, and shell commands directly.""");
        setParameters(buildParameters());
    }

    @Override
    public ToolCallResult execute(String arguments) {
        long startTime = System.currentTimeMillis();
        try {
            JsonNode params = OBJECT_MAPPER.readTree(arguments);
            String action = readString(params, "action");

            return switch (action) {
                case "search" -> handleSearch(params, startTime);
                case "get" -> handleGet(params, startTime);
                default -> ToolCallResult.failed("Error: 'action' must be 'search' or 'get'")
                        .withDuration(System.currentTimeMillis() - startTime);
            };
        } catch (Exception e) {
            LOGGER.warn("MdMemory tool failed: {}", e.getMessage());
            return ToolCallResult.failed("Failed: " + e.getMessage(), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private ToolCallResult handleSearch(JsonNode params, long startTime) {
        String query = readString(params, "query");
        if (query.isBlank()) {
            return fail("'query' is required for search", startTime);
        }

        List<MemoryEntry> results = provider.searchMemories(query);
        if (results.isEmpty()) {
            return ToolCallResult.completed("No memories matching '" + query + "'")
                    .withDuration(System.currentTimeMillis() - startTime)
                    .withStats("action", "search")
                    .withStats("count", 0);
        }
        String result = results.stream()
                .map(MemoryEntry::toSummary)
                .collect(Collectors.joining("\n"));
        return ToolCallResult.completed("Found " + results.size() + " memories:\n" + result)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("action", "search")
                .withStats("count", results.size());
    }

    private ToolCallResult handleGet(JsonNode params, long startTime) {
        String path = readString(params, "path");
        if (path.isBlank()) {
            return fail("'path' is required for get", startTime);
        }
        int from = readInt(params, "from", 0);
        int lines = readInt(params, "lines", 0);

        String content = provider.readMemoryFile(path, from, lines);
        if (content == null) {
            return ToolCallResult.failed("Memory file not found: " + path)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
        return ToolCallResult.completed(content)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("action", "get")
                .withStats("path", path);
    }

    private String readString(JsonNode params, String field) {
        return params.has(field) ? params.get(field).asText() : "";
    }

    private int readInt(JsonNode params, String field, int defaultValue) {
        return params.has(field) ? params.get(field).asInt(defaultValue) : defaultValue;
    }

    private ToolCallResult fail(String message, long startTime) {
        return ToolCallResult.failed("Error: " + message)
                .withDuration(System.currentTimeMillis() - startTime);
    }

    public static class Builder extends ToolCall.Builder<Builder, MdMemoryTool> {
        private MdMemoryProvider provider;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder provider(MdMemoryProvider provider) {
            this.provider = provider;
            return this;
        }

        public MdMemoryTool build() {
            if (provider == null) {
                throw new IllegalStateException("MdMemoryProvider is required");
            }
            return new MdMemoryTool(provider);
        }
    }
}
