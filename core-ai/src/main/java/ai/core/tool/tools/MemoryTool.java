package ai.core.tool.tools;

import ai.core.memory.MemoryProvider;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.ToolCallResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author xander
 */
public final class MemoryTool extends ToolCall {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryTool.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final String TOOL_NAME = "memory_tool";

    public static Builder builder() {
        return new Builder();
    }

    private static List<ToolCallParameter> buildParameters() {
        return List.of(
                ToolCallParameter.builder()
                        .name("action")
                        .description("'read' to view current memory content, 'edit' to modify existing content")
                        .type(ToolCallParameterType.STRING)
                        .classType(String.class)
                        .required(true)
                        .enums(List.of("read", "edit"))
                        .build(),
                ToolCallParameter.builder()
                        .name("old_string")
                        .description("For edit: the exact text to find and replace in the memory file")
                        .type(ToolCallParameterType.STRING)
                        .classType(String.class)
                        .required(false)
                        .build(),
                ToolCallParameter.builder()
                        .name("new_string")
                        .description("For edit: the replacement text. Use empty string to delete content")
                        .type(ToolCallParameterType.STRING)
                        .classType(String.class)
                        .required(false)
                        .build()
        );
    }

    private final MemoryProvider provider;

    private MemoryTool(MemoryProvider provider) {
        this.provider = provider;
        setName(TOOL_NAME);
        setNeedAuth(true);
        setDescription("""
                Manage persistent memory (MEMORY.md) that survives across sessions.

                Workflow:
                1. Use action='read' to view the full content and structure
                2. Use action='edit' with old_string/new_string to add, update, or remove content in the right section

                To add new content: read first, then edit to insert at the appropriate section.
                To update: read first, find the existing entry, then edit to replace it.
                To remove: edit with new_string as empty string.
                Always maintain the markdown structure when editing.""");
        setParameters(buildParameters());
    }

    @Override
    public ToolCallResult execute(String arguments) {
        long startTime = System.currentTimeMillis();
        try {
            JsonNode params = OBJECT_MAPPER.readTree(arguments);
            String action = params.has("action") ? params.get("action").asText() : "";

            return switch (action) {
                case "read" -> handleRead(startTime);
                case "edit" -> handleEdit(params, startTime);
                default -> ToolCallResult.failed("Error: 'action' must be 'read' or 'edit'")
                        .withDuration(System.currentTimeMillis() - startTime);
            };
        } catch (Exception e) {
            LOGGER.warn("Memory tool failed: {}", e.getMessage());
            return ToolCallResult.failed("Failed: " + e.getMessage(), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private ToolCallResult handleRead(long startTime) {
        String raw = provider.readRaw();
        if (raw.isBlank()) {
            return ToolCallResult.completed("Memory file is empty.")
                    .withDuration(System.currentTimeMillis() - startTime)
                    .withStats("action", "read");
        }
        return ToolCallResult.completed(raw)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("action", "read");
    }

    private ToolCallResult handleEdit(JsonNode params, long startTime) {
        String oldString = params.has("old_string") ? params.get("old_string").asText() : null;
        String newString = params.has("new_string") ? params.get("new_string").asText() : null;
        if (oldString == null || newString == null) {
            return ToolCallResult.failed("Error: 'old_string' and 'new_string' are required for edit action")
                    .withDuration(System.currentTimeMillis() - startTime);
        }
        if (oldString.equals(newString)) {
            return ToolCallResult.failed("Error: old_string and new_string must be different")
                    .withDuration(System.currentTimeMillis() - startTime);
        }
        String result = provider.edit(oldString, newString);
        LOGGER.debug("Edited memory: {}", result);
        return ToolCallResult.completed(result)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("action", "edit");
    }

    public static class Builder extends ToolCall.Builder<Builder, MemoryTool> {
        private MemoryProvider provider;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder provider(MemoryProvider provider) {
            this.provider = provider;
            return this;
        }

        public MemoryTool build() {
            if (provider == null) {
                throw new IllegalStateException("provider is required");
            }
            return new MemoryTool(provider);
        }
    }
}
