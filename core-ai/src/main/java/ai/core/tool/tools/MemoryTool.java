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

    private final MemoryProvider provider;

    private MemoryTool(MemoryProvider provider) {
        this.provider = provider;
        setName(TOOL_NAME);
        setNeedAuth(true);
        setDescription("""
                Save or forget a fact in persistent memory that survives across sessions.
                Use action=save when the user:
                - Expresses a preference (language, coding style, tools, workflows)
                - Tells you about their project (tech stack, architecture, conventions)
                - Corrects your behavior and wants the correction remembered
                - Explicitly asks you to remember something
                Use action=forget when the user:
                - Asks you to forget or stop remembering something
                - Wants to remove outdated or incorrect information
                Do NOT use this tool for transient or session-specific information.""");
        setParameters(buildParameters());
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ToolCallResult execute(String arguments) {
        long startTime = System.currentTimeMillis();
        try {
            JsonNode params = OBJECT_MAPPER.readTree(arguments);

            String action = params.has("action") ? params.get("action").asText() : "";
            String content = params.has("content") ? params.get("content").asText() : "";

            if (content.isBlank()) {
                return ToolCallResult.failed("Error: 'content' parameter is required")
                        .withDuration(System.currentTimeMillis() - startTime);
            }

            return switch (action) {
                case "save" -> handleSave(content, startTime);
                case "forget" -> handleForget(content, startTime);
                default -> ToolCallResult.failed("Error: 'action' must be 'save' or 'forget'")
                        .withDuration(System.currentTimeMillis() - startTime);
            };
        } catch (Exception e) {
            LOGGER.warn("Memory tool failed: {}", e.getMessage());
            return ToolCallResult.failed("Failed: " + e.getMessage(), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private ToolCallResult handleSave(String content, long startTime) {
        provider.save(content);
        LOGGER.debug("Saved memory: {}", content);
        return ToolCallResult.completed("Remembered: " + content)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("action", "save")
                .withStats("content", content);
    }

    private ToolCallResult handleForget(String keyword, long startTime) {
        int removed = provider.remove(keyword);
        LOGGER.debug("Removed {} entries matching: {}", removed, keyword);
        String message = removed > 0
                ? "Removed " + removed + " matching entries for '" + keyword + "'"
                : "No entries matched '" + keyword + "'";
        return ToolCallResult.completed(message)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("action", "forget")
                .withStats("keyword", keyword)
                .withStats("removed", removed);
    }

    private static List<ToolCallParameter> buildParameters() {
        return List.of(
                ToolCallParameter.builder()
                        .name("action")
                        .description("The action to perform: 'save' to remember a fact, 'forget' to remove entries matching a keyword")
                        .type(ToolCallParameterType.STRING)
                        .classType(String.class)
                        .required(true)
                        .enums(List.of("save", "forget"))
                        .build(),
                ToolCallParameter.builder()
                        .name("content")
                        .description("For save: the fact or preference to remember. For forget: keyword to match and remove entries.")
                        .type(ToolCallParameterType.STRING)
                        .classType(String.class)
                        .required(true)
                        .build()
        );
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
